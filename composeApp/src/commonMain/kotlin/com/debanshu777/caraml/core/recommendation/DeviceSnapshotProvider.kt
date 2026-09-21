package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.platform.BackendCapability
import com.debanshu777.caraml.core.platform.BackendCapabilitySource
import com.debanshu777.caraml.core.platform.DeviceCapabilities
import com.debanshu777.caraml.core.platform.DeviceSnapshot
import com.debanshu777.caraml.core.platform.HardwareProfile
import com.debanshu777.caraml.core.platform.MemoryTopology
import com.debanshu777.caraml.core.platform.PowerPolicyState
import com.debanshu777.caraml.core.platform.ResourceSnapshot
import com.debanshu777.caraml.core.platform.ResourcePoolConfidence
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

    /** Refreshes dynamic resource/storage facts without repeating static or native backend probes. */
    suspend fun refreshResources(previous: DeviceSnapshot): DeviceSnapshot = withContext(probeDispatcher) {
        captureBlocking(reusedHardwareProfile = previous.hardwareProfile)
    }

    private fun captureBlocking(reusedHardwareProfile: HardwareProfile? = null): DeviceSnapshot {
        val providerEvidence = mutableListOf<Evidence>()
        val profile = reusedHardwareProfile ?: readHardware(providerEvidence)
        val backends = if (reusedHardwareProfile == null) {
            readBackends(profile.backends, providerEvidence)
        } else {
            profile.backends
        }
        val resolvedProfile = if (reusedHardwareProfile == null) profile.withBackends(backends) else profile

        val rawResources = readResources(providerEvidence)
        val now = clock()
        val normalized = normalizeResources(rawResources, resolvedProfile.memoryTopology, providerEvidence)
        val storage = readStorage(providerEvidence)
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
        val resources = normalized.withStorageAndEvidence(
            storage.bytesOrNull,
            storage.confidenceOrNull,
            providerEvidence,
        )

        val hostBudget = resources.additionalAllocatableHostBytes?.let { allocatable ->
            val minimum = resources.platformMinimumReserveHostBytes
            if (minimum == null || resources.confidence.host == null) null else computeBaseBudget(
                allocatable = allocatable,
                osThreshold = resources.osPressureReserveHostBytes ?: 0L,
                noiseP95 = resources.observedAppFootprintNoiseP95Bytes ?: 0L,
                minimum = minimum,
            )
        }
        val host = budgetReading(
            hostBudget,
            resources.confidence.host,
            wasPresent = rawResources.additionalAllocatableHostBytes != null,
        )
        val resourceGpu = budgetReading(
            resources.additionalAllocatableGpuBytes,
            resources.confidence.gpu,
            wasPresent = rawResources.additionalAllocatableGpuBytes != null,
        )
        val gpu = minimumBudget(resourceGpu, backendGpuBudget(backends, providerEvidence))
        val (baseHost, baseGpu, baseShared, budgetConfidence) = when (resolvedProfile.memoryTopology) {
            MemoryTopology.UNIFIED -> {
                val shared = minimumBudget(host, gpu)
                BudgetResult(
                    host = null,
                    gpu = null,
                    shared = shared.bytesOrNull,
                    confidence = ResourcePoolConfidence(shared = shared.confidenceOrNull),
                )
            }
            MemoryTopology.DISCRETE -> BudgetResult(
                host = host.bytesOrNull,
                gpu = gpu.bytesOrNull,
                shared = null,
                confidence = ResourcePoolConfidence(
                    host = host.confidenceOrNull,
                    gpu = gpu.confidenceOrNull,
                ),
            )
            MemoryTopology.UNKNOWN -> BudgetResult(
                host = host.bytesOrNull,
                gpu = null,
                shared = null,
                confidence = ResourcePoolConfidence(host = host.confidenceOrNull),
            )
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
            baseStorageBudgetBytes = storage.bytesOrNull?.let(::computeStorageBudget),
            isFresh = fresh,
            evidence = allEvidence,
            budgetConfidence = budgetConfidence.copy(
                storage = storage.confidenceOrNull,
            ),
        )
    }

    private fun readHardware(evidence: MutableList<Evidence>): HardwareProfile = try {
        hardwareProfileSource().revalidated()
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
        val reported = backendCapabilitySource.capabilities().asSequence()
            .take(RecommendationPolicyV1.MAX_BACKEND_CAPABILITIES + 1)
            .toList()
        when {
            reported.isEmpty() -> fallback.toList()
            reported.size > RecommendationPolicyV1.MAX_BACKEND_CAPABILITIES -> {
                evidence += Evidence(
                    AssessmentReason.ASSESSMENT_GRAPH_INVALID,
                    Confidence.LOW,
                    "backend-capability-limit-exceeded",
                )
                reported
            }
            else -> mergeBackends(fallback, reported)
        }
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

    private fun readStorage(evidence: MutableList<Evidence>): BudgetReading = try {
        storageBytesSource().takeIf { it > 0L }?.let {
            BudgetReading.Known(it, Confidence.HIGH)
        } ?: run {
            evidence += Evidence(
                AssessmentReason.INVALID_STORAGE_READING,
                Confidence.LOW,
                "free-storage-not-positive",
            )
            BudgetReading.Untrusted
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        evidence += Evidence(
            AssessmentReason.RESOURCE_READING_UNAVAILABLE,
            Confidence.LOW,
            "free-storage-unavailable",
        )
        BudgetReading.Absent
    }

    private fun readResources(evidence: MutableList<Evidence>): ResourceSnapshot = try {
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
            capturedAtEpochMs = clock(),
            evidence = emptyList(),
        )
    }

    private fun backendGpuBudget(
        backends: List<BackendCapability>,
        evidence: MutableList<Evidence>,
    ): BudgetReading {
        var headroom: BudgetReading = BudgetReading.Absent
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
                headroom = minimumBudget(headroom, BudgetReading.Untrusted)
            } else {
                val reading = backend.headroomConfidence?.let {
                    BudgetReading.Known(value, it)
                } ?: BudgetReading.Untrusted
                if (reading == BudgetReading.Untrusted) {
                    evidence += Evidence(
                        AssessmentReason.RESOURCE_READING_UNAVAILABLE,
                        Confidence.LOW,
                        "backend-${backend.kind}-headroom-confidence",
                    )
                }
                headroom = minimumBudget(headroom, reading)
            }
        }
        return headroom
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
            confidence = ResourcePoolConfidence(
                host = value.confidence.host.takeIf { host != null },
                gpu = value.confidence.gpu.takeIf { gpu != null },
                process = value.confidence.process.takeIf { process != null },
            ),
        ).withCollectionLimitExceeded(value.collectionLimitExceeded)
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

    private fun minimumBudget(first: BudgetReading, second: BudgetReading): BudgetReading = when {
        first == BudgetReading.Untrusted || second == BudgetReading.Untrusted -> BudgetReading.Untrusted
        first is BudgetReading.Known && second is BudgetReading.Known -> BudgetReading.Known(
            bytes = minOf(first.bytes, second.bytes),
            confidence = minConfidence(first.confidence, second.confidence),
        )
        first is BudgetReading.Known -> first
        second is BudgetReading.Known -> second
        else -> BudgetReading.Absent
    }

    private fun minConfidence(first: Confidence, second: Confidence): Confidence =
        if (first.ordinal <= second.ordinal) first else second

    private fun budgetReading(
        bytes: Long?,
        confidence: Confidence?,
        wasPresent: Boolean = bytes != null,
    ): BudgetReading = when {
        !wasPresent -> BudgetReading.Absent
        bytes == null || bytes < 0L || confidence == null -> BudgetReading.Untrusted
        else -> BudgetReading.Known(bytes, confidence)
    }

    private sealed interface BudgetReading {
        data object Absent : BudgetReading
        data object Untrusted : BudgetReading
        data class Known(val bytes: Long, val confidence: Confidence) : BudgetReading

        val bytesOrNull: Long?
            get() = (this as? Known)?.bytes

        val confidenceOrNull: Confidence?
            get() = (this as? Known)?.confidence
    }

    private data class BudgetResult(
        val host: Long?,
        val gpu: Long?,
        val shared: Long?,
        val confidence: ResourcePoolConfidence,
    )
}
