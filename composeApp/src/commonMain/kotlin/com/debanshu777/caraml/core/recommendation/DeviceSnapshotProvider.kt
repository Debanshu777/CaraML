package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.platform.BackendCapability
import com.debanshu777.caraml.core.platform.BackendCapabilitySource
import com.debanshu777.caraml.core.platform.DeviceCapabilities
import com.debanshu777.caraml.core.platform.DeviceSnapshot
import com.debanshu777.caraml.core.platform.HardwareProfile
import com.debanshu777.caraml.core.platform.MemoryTopology
import com.debanshu777.caraml.core.platform.PowerPolicyState
import com.debanshu777.caraml.core.platform.ResourceSnapshot
import com.debanshu777.caraml.core.platform.ThermalState
import com.debanshu777.caraml.core.platform.computeBaseBudget
import com.debanshu777.caraml.core.platform.computeStorageBudget
import com.debanshu777.caraml.core.platform.validatedHardwareProfile
import com.debanshu777.huggingfacemanager.download.StoragePathProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

class DeviceSnapshotProvider internal constructor(
    private val hardwareProfileSource: () -> HardwareProfile,
    private val resourceSnapshotSource: () -> ResourceSnapshot,
    private val backendCapabilitySource: BackendCapabilitySource,
    private val storageBytesSource: () -> Long,
    private val probeDispatcher: CoroutineDispatcher,
    private val clock: () -> Long,
) {
    constructor(
        capabilities: DeviceCapabilities,
        backendCapabilitySource: BackendCapabilitySource,
        storage: StoragePathProvider,
        probeDispatcher: CoroutineDispatcher,
        clock: () -> Long,
    ) : this(
        hardwareProfileSource = capabilities::getHardwareProfile,
        resourceSnapshotSource = capabilities::getResourceSnapshot,
        backendCapabilitySource = backendCapabilitySource,
        storageBytesSource = storage::getAvailableStorageBytes,
        probeDispatcher = probeDispatcher,
        clock = clock,
    )

    suspend fun capture(): DeviceSnapshot = withContext(probeDispatcher) {
        captureBlocking()
    }

    private fun captureBlocking(): DeviceSnapshot {
        val now = clock()
        val providerEvidence = mutableListOf<Evidence>()
        val profile = readHardware(providerEvidence)
        val backends = readBackends(profile.backends, providerEvidence)
        val resolvedProfile = profile.withBackends(backends)

        val rawResources = readResources(now, providerEvidence)
        val normalized = normalizeResources(rawResources, resolvedProfile.memoryTopology, providerEvidence)
        val storageBytes = readStorage(providerEvidence)
        val fresh = normalized.isFreshAt(now)
        if (!fresh) {
            providerEvidence += Evidence(
                AssessmentReason.RESOURCE_SNAPSHOT_STALE,
                Confidence.LOW,
                "captured=${normalized.capturedAtEpochMs},now=$now",
            )
        }
        if (normalized.lowMemory == true) {
            providerEvidence += Evidence(
                AssessmentReason.LOW_MEMORY_PRESSURE,
                Confidence.HIGH,
                "platform-low-memory-signal",
            )
        }
        val resources = normalized.withStorageAndEvidence(storageBytes, providerEvidence)

        val hostBudget = resources.additionalAllocatableHostBytes?.let { allocatable ->
            val minimum = resources.platformMinimumReserveHostBytes
            if (minimum == null) null else computeBaseBudget(
                allocatable = allocatable,
                osThreshold = resources.osPressureReserveHostBytes ?: 0L,
                noiseP95 = resources.observedAppFootprintNoiseP95Bytes ?: 0L,
                minimum = minimum,
            )
        }
        val backendGpuBudget = backendGpuBudget(backends, providerEvidence)
        val gpuBudget = minKnown(resources.additionalAllocatableGpuBytes, backendGpuBudget)
        val (baseHost, baseGpu, baseShared) = when (resolvedProfile.memoryTopology) {
            MemoryTopology.UNIFIED -> Triple(null, null, minKnown(hostBudget, gpuBudget))
            MemoryTopology.DISCRETE -> Triple(hostBudget, gpuBudget, null)
            MemoryTopology.UNKNOWN -> Triple(hostBudget, null, null)
        }
        val allEvidence = buildList {
            addAll(resolvedProfile.evidence)
            addAll(resources.evidence)
            addAll(providerEvidence)
            backends.forEach { addAll(it.evidence) }
        }.distinct()

        return DeviceSnapshot(
            hardwareProfile = resolvedProfile,
            resources = resources,
            baseHostBudgetBytes = baseHost,
            baseGpuBudgetBytes = baseGpu,
            baseSharedBudgetBytes = baseShared,
            baseStorageBudgetBytes = storageBytes?.let(::computeStorageBudget),
            isFresh = fresh,
            evidence = allEvidence,
        )
    }

    private fun readHardware(evidence: MutableList<Evidence>): HardwareProfile = try {
        hardwareProfileSource()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        val unavailable = Evidence(
            AssessmentReason.RESOURCE_READING_UNAVAILABLE,
            Confidence.LOW,
            "hardware-profile-source-failed",
        )
        evidence += unavailable
        validatedHardwareProfile(
            cpuArchitecture = "unknown",
            logicalCoreCountReading = 1,
            performanceCoreCountReading = null,
            instructionSets = emptySet(),
            backends = emptyList(),
            memoryTopology = MemoryTopology.UNKNOWN,
            evidence = listOf(unavailable),
        )
    }

    private fun readBackends(
        fallback: List<BackendCapability>,
        evidence: MutableList<Evidence>,
    ): List<BackendCapability> = try {
        val reported = backendCapabilitySource.capabilities().toList()
        if (reported.isEmpty()) fallback.toList() else mergeBackends(fallback, reported)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        evidence += Evidence(
            AssessmentReason.BACKEND_CAPABILITY_UNKNOWN,
            Confidence.LOW,
            "backend-capability-source-failed",
        )
        fallback.toList()
    }

    private fun readStorage(evidence: MutableList<Evidence>): Long? = try {
        storageBytesSource().takeIf { it > 0L } ?: run {
            evidence += Evidence(
                AssessmentReason.INVALID_STORAGE_READING,
                Confidence.LOW,
                "free-storage-not-positive",
            )
            null
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        evidence += Evidence(
            AssessmentReason.RESOURCE_READING_UNAVAILABLE,
            Confidence.LOW,
            "free-storage-unavailable",
        )
        null
    }

    private fun readResources(
        now: Long,
        evidence: MutableList<Evidence>,
    ): ResourceSnapshot = try {
        resourceSnapshotSource()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        evidence += Evidence(
            AssessmentReason.RESOURCE_READING_UNAVAILABLE,
            Confidence.LOW,
            "resource-snapshot-source-failed",
        )
        ResourceSnapshot(
            additionalAllocatableHostBytes = null,
            additionalAllocatableGpuBytes = null,
            currentProcessBytes = null,
            freeStorageBytes = null,
            osPressureReserveHostBytes = null,
            observedAppFootprintNoiseP95Bytes = null,
            platformMinimumReserveHostBytes = null,
            lowMemory = null,
            thermalState = ThermalState.UNKNOWN,
            powerPolicyState = PowerPolicyState.UNKNOWN,
            capturedAtEpochMs = now,
            evidence = emptyList(),
        )
    }

    private fun backendGpuBudget(
        backends: List<BackendCapability>,
        evidence: MutableList<Evidence>,
    ): Long? {
        val headrooms = mutableListOf<Long>()
        for (backend in backends) {
            if (backend.kind == com.debanshu777.caraml.core.platform.BackendKind.CPU ||
                backend.status != com.debanshu777.caraml.core.platform.BackendStatus.AVAILABLE
            ) continue
            val value = backend.additionalAllocatableBytes ?: continue
            if (value < 0L) {
                evidence += Evidence(
                    AssessmentReason.INVALID_OS_MEMORY_READING,
                    Confidence.LOW,
                    "backend-${backend.kind}-headroom",
                )
            } else {
                headrooms += value
            }
        }
        return headrooms.minOrNull()
    }

    private fun normalizeResources(
        value: ResourceSnapshot,
        topology: MemoryTopology,
        evidence: MutableList<Evidence>,
    ): ResourceSnapshot {
        fun memoryReading(raw: Long?, name: String): Long? {
            if (raw == null) return null
            if (raw >= 0L) return raw
            evidence += Evidence(
                AssessmentReason.INVALID_OS_MEMORY_READING,
                Confidence.LOW,
                name,
            )
            return null
        }

        val host = memoryReading(value.additionalAllocatableHostBytes, "allocatable-host")
        val gpu = memoryReading(value.additionalAllocatableGpuBytes, "allocatable-gpu")
        val process = memoryReading(value.currentProcessBytes, "current-process")
        val threshold = memoryReading(value.osPressureReserveHostBytes, "os-pressure-reserve")
        val noise = memoryReading(value.observedAppFootprintNoiseP95Bytes, "process-noise-p95")
        val minimum = memoryReading(value.platformMinimumReserveHostBytes, "platform-minimum-reserve")
        if (host == null || topology == MemoryTopology.DISCRETE && gpu == null) {
            evidence += Evidence(
                AssessmentReason.RESOURCE_READING_UNAVAILABLE,
                Confidence.LOW,
                "required-memory-pool-unavailable",
            )
        }
        return ResourceSnapshot(
            additionalAllocatableHostBytes = host,
            additionalAllocatableGpuBytes = gpu,
            currentProcessBytes = process,
            freeStorageBytes = null,
            osPressureReserveHostBytes = threshold,
            observedAppFootprintNoiseP95Bytes = noise,
            platformMinimumReserveHostBytes = minimum,
            lowMemory = value.lowMemory,
            thermalState = value.thermalState,
            powerPolicyState = value.powerPolicyState,
            capturedAtEpochMs = value.capturedAtEpochMs,
            evidence = value.evidence,
        )
    }

    private fun mergeBackends(
        fallback: List<BackendCapability>,
        reported: List<BackendCapability>,
    ): List<BackendCapability> {
        val merged = linkedMapOf<com.debanshu777.caraml.core.platform.BackendKind, BackendCapability>()
        fallback.forEach { merged[it.kind] = it }
        reported.forEach { merged[it.kind] = it }
        return merged.values.toList()
    }

    private fun minKnown(first: Long?, second: Long?): Long? = when {
        first != null && second != null -> minOf(first, second)
        first != null -> first
        else -> second
    }
}
