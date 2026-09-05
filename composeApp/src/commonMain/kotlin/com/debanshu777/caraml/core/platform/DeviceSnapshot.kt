package com.debanshu777.caraml.core.platform

import com.debanshu777.caraml.core.recommendation.AssessmentReason
import com.debanshu777.caraml.core.recommendation.Confidence
import com.debanshu777.caraml.core.recommendation.Evidence

internal const val MAX_LOGICAL_CORE_COUNT = 1_024
internal const val RESOURCE_SNAPSHOT_MAX_AGE_MS = 30_000L

enum class MemoryTopology {
    UNIFIED,
    DISCRETE,
    UNKNOWN,
}

enum class BackendKind {
    CPU,
    METAL,
    VULKAN,
    CUDA,
    OTHER,
}

enum class BackendStatus {
    AVAILABLE,
    UNAVAILABLE,
    UNKNOWN,
}

enum class ThermalState {
    NOMINAL,
    FAIR,
    SERIOUS,
    CRITICAL,
    UNKNOWN,
}

enum class PowerPolicyState {
    NORMAL,
    POWER_SAVER,
    UNKNOWN,
}

@ConsistentCopyVisibility
data class BackendCapability private constructor(
    val kind: BackendKind,
    val status: BackendStatus,
    val additionalAllocatableBytes: Long?,
    val evidence: List<Evidence>,
) {
    constructor(
        kind: BackendKind,
        status: BackendStatus,
        additionalAllocatableBytes: Long?,
        evidence: Collection<Evidence>,
    ) : this(
        kind = kind,
        status = status,
        additionalAllocatableBytes = additionalAllocatableBytes,
        evidence = evidence.toList(),
    )
}

@ConsistentCopyVisibility
data class HardwareProfile private constructor(
    val cpuArchitecture: String,
    val logicalCoreCount: Int,
    val performanceCoreCount: Int?,
    val instructionSets: Set<String>,
    val backends: List<BackendCapability>,
    val memoryTopology: MemoryTopology,
    val evidence: List<Evidence>,
) {
    constructor(
        cpuArchitecture: String,
        logicalCoreCount: Int,
        performanceCoreCount: Int?,
        instructionSets: Collection<String>,
        backends: Collection<BackendCapability>,
        memoryTopology: MemoryTopology,
        evidence: Collection<Evidence>,
    ) : this(
        cpuArchitecture = cpuArchitecture,
        logicalCoreCount = logicalCoreCount,
        performanceCoreCount = performanceCoreCount,
        instructionSets = instructionSets.toSet(),
        backends = backends.toList(),
        memoryTopology = memoryTopology,
        evidence = evidence.toList(),
    )

    internal fun withBackends(value: Collection<BackendCapability>): HardwareProfile =
        HardwareProfile(
            cpuArchitecture = cpuArchitecture,
            logicalCoreCount = logicalCoreCount,
            performanceCoreCount = performanceCoreCount,
            instructionSets = instructionSets,
            backends = value,
            memoryTopology = memoryTopology,
            evidence = evidence,
        )
}

@ConsistentCopyVisibility
data class ResourceSnapshot private constructor(
    val additionalAllocatableHostBytes: Long?,
    val additionalAllocatableGpuBytes: Long?,
    val currentProcessBytes: Long?,
    val freeStorageBytes: Long?,
    val osPressureReserveHostBytes: Long?,
    val observedAppFootprintNoiseP95Bytes: Long?,
    val platformMinimumReserveHostBytes: Long?,
    val lowMemory: Boolean?,
    val thermalState: ThermalState,
    val powerPolicyState: PowerPolicyState,
    val capturedAtEpochMs: Long,
    val evidence: List<Evidence>,
) {
    constructor(
        additionalAllocatableHostBytes: Long?,
        additionalAllocatableGpuBytes: Long?,
        currentProcessBytes: Long?,
        freeStorageBytes: Long?,
        osPressureReserveHostBytes: Long?,
        observedAppFootprintNoiseP95Bytes: Long?,
        platformMinimumReserveHostBytes: Long?,
        lowMemory: Boolean?,
        thermalState: ThermalState,
        powerPolicyState: PowerPolicyState,
        capturedAtEpochMs: Long,
        evidence: Collection<Evidence>,
    ) : this(
        additionalAllocatableHostBytes = additionalAllocatableHostBytes,
        additionalAllocatableGpuBytes = additionalAllocatableGpuBytes,
        currentProcessBytes = currentProcessBytes,
        freeStorageBytes = freeStorageBytes,
        osPressureReserveHostBytes = osPressureReserveHostBytes,
        observedAppFootprintNoiseP95Bytes = observedAppFootprintNoiseP95Bytes,
        platformMinimumReserveHostBytes = platformMinimumReserveHostBytes,
        lowMemory = lowMemory,
        thermalState = thermalState,
        powerPolicyState = powerPolicyState,
        capturedAtEpochMs = capturedAtEpochMs,
        evidence = evidence.toList(),
    )

    fun isFreshAt(
        nowEpochMs: Long,
        maxAgeMs: Long = RESOURCE_SNAPSHOT_MAX_AGE_MS,
    ): Boolean {
        if (capturedAtEpochMs < 0L || nowEpochMs < capturedAtEpochMs || maxAgeMs < 0L) return false
        return nowEpochMs - capturedAtEpochMs <= maxAgeMs
    }

    internal fun withStorageAndEvidence(
        storageBytes: Long?,
        additionalEvidence: Collection<Evidence>,
    ): ResourceSnapshot = ResourceSnapshot(
        additionalAllocatableHostBytes = additionalAllocatableHostBytes,
        additionalAllocatableGpuBytes = additionalAllocatableGpuBytes,
        currentProcessBytes = currentProcessBytes,
        freeStorageBytes = storageBytes,
        osPressureReserveHostBytes = osPressureReserveHostBytes,
        observedAppFootprintNoiseP95Bytes = observedAppFootprintNoiseP95Bytes,
        platformMinimumReserveHostBytes = platformMinimumReserveHostBytes,
        lowMemory = lowMemory,
        thermalState = thermalState,
        powerPolicyState = powerPolicyState,
        capturedAtEpochMs = capturedAtEpochMs,
        evidence = evidence + additionalEvidence,
    )
}

@ConsistentCopyVisibility
data class DeviceSnapshot private constructor(
    val hardwareProfile: HardwareProfile,
    val resources: ResourceSnapshot,
    val baseHostBudgetBytes: Long?,
    val baseGpuBudgetBytes: Long?,
    val baseSharedBudgetBytes: Long?,
    val baseStorageBudgetBytes: Long?,
    val isFresh: Boolean,
    val evidence: List<Evidence>,
) {
    constructor(
        hardwareProfile: HardwareProfile,
        resources: ResourceSnapshot,
        baseHostBudgetBytes: Long?,
        baseGpuBudgetBytes: Long?,
        baseSharedBudgetBytes: Long?,
        baseStorageBudgetBytes: Long?,
        isFresh: Boolean,
        evidence: Collection<Evidence>,
    ) : this(
        hardwareProfile = hardwareProfile,
        resources = resources,
        baseHostBudgetBytes = baseHostBudgetBytes,
        baseGpuBudgetBytes = baseGpuBudgetBytes,
        baseSharedBudgetBytes = baseSharedBudgetBytes,
        baseStorageBudgetBytes = baseStorageBudgetBytes,
        isFresh = isFresh,
        evidence = evidence.toList(),
    )
}

internal fun fallbackPerformanceCoreCount(logicalCoreCount: Int): Int? =
    logicalCoreCount
        .takeIf { it in 1..MAX_LOGICAL_CORE_COUNT }
        ?.let { logical -> maxOf(1, logical / 2).coerceAtMost(logical) }

internal fun validatedHardwareProfile(
    cpuArchitecture: String,
    logicalCoreCountReading: Int,
    performanceCoreCountReading: Int?,
    instructionSets: Collection<String>,
    backends: Collection<BackendCapability>,
    memoryTopology: MemoryTopology,
    evidence: Collection<Evidence> = emptyList(),
): HardwareProfile {
    val validationEvidence = evidence.toMutableList()
    val logicalCoreCount = logicalCoreCountReading.takeIf { it in 1..MAX_LOGICAL_CORE_COUNT }
        ?: 1.also {
            validationEvidence += invalidCoreEvidence(
                "logical-core-count=$logicalCoreCountReading",
            )
        }
    val performanceCoreCount = performanceCoreCountReading?.takeIf { it in 1..logicalCoreCount }
        ?: run {
            if (performanceCoreCountReading != null) {
                validationEvidence += invalidCoreEvidence(
                    "performance-core-count=$performanceCoreCountReading,logical-core-count=$logicalCoreCountReading",
                )
            }
            null
        }
    return HardwareProfile(
        cpuArchitecture = cpuArchitecture.take(128),
        logicalCoreCount = logicalCoreCount,
        performanceCoreCount = performanceCoreCount,
        instructionSets = instructionSets.filter { it.length <= 64 }.toSet(),
        backends = backends,
        memoryTopology = memoryTopology,
        evidence = validationEvidence,
    )
}

private fun invalidCoreEvidence(detail: String) = Evidence(
    reason = AssessmentReason.INVALID_CORE_COUNT,
    confidence = Confidence.LOW,
    detail = detail,
)
