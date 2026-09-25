package com.debanshu777.caraml.core.platform

import com.debanshu777.caraml.core.recommendation.AssessmentReason
import com.debanshu777.caraml.core.recommendation.BoundedCollectionSnapshot
import com.debanshu777.caraml.core.recommendation.Confidence
import com.debanshu777.caraml.core.recommendation.Evidence
import com.debanshu777.caraml.core.recommendation.RecommendationPolicyV1
import com.debanshu777.caraml.core.recommendation.boundedCollectionSnapshot

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

data class ResourcePoolConfidence(
    val host: Confidence? = null,
    val gpu: Confidence? = null,
    val shared: Confidence? = null,
    val storage: Confidence? = null,
    val process: Confidence? = null,
)

@ConsistentCopyVisibility
data class BackendCapability private constructor(
    val kind: BackendKind,
    val status: BackendStatus,
    val additionalAllocatableBytes: Long?,
    val availabilityConfidence: Confidence?,
    val headroomConfidence: Confidence?,
    val evidence: List<Evidence>,
    internal val collectionLimitExceeded: Boolean,
) {
    constructor(
        kind: BackendKind,
        status: BackendStatus,
        additionalAllocatableBytes: Long?,
        availabilityConfidence: Confidence?,
        headroomConfidence: Confidence?,
        evidence: Collection<Evidence>,
    ) : this(
        kind = kind,
        status = status,
        additionalAllocatableBytes = additionalAllocatableBytes,
        availabilityConfidence = availabilityConfidence,
        headroomConfidence = headroomConfidence,
        evidence = boundedCollectionSnapshot(evidence, RecommendationPolicyV1.MAX_EVIDENCE_ENTRIES),
    )

    private constructor(
        kind: BackendKind,
        status: BackendStatus,
        additionalAllocatableBytes: Long?,
        availabilityConfidence: Confidence?,
        headroomConfidence: Confidence?,
        evidence: BoundedCollectionSnapshot<Evidence>,
    ) : this(
        kind = kind,
        status = status,
        additionalAllocatableBytes = additionalAllocatableBytes,
        availabilityConfidence = availabilityConfidence,
        headroomConfidence = headroomConfidence,
        evidence = evidence.values,
        collectionLimitExceeded = evidence.limitExceeded,
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
    internal val collectionLimitExceeded: Boolean,
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
        instructionSets = boundedCollectionSnapshot(instructionSets, RecommendationPolicyV1.MAX_INSTRUCTION_SETS),
        backends = boundedCollectionSnapshot(backends, RecommendationPolicyV1.MAX_BACKEND_CAPABILITIES),
        memoryTopology = memoryTopology,
        evidence = boundedCollectionSnapshot(evidence, RecommendationPolicyV1.MAX_EVIDENCE_ENTRIES),
    )

    private constructor(
        cpuArchitecture: String,
        logicalCoreCount: Int,
        performanceCoreCount: Int?,
        instructionSets: BoundedCollectionSnapshot<String>,
        backends: BoundedCollectionSnapshot<BackendCapability>,
        memoryTopology: MemoryTopology,
        evidence: BoundedCollectionSnapshot<Evidence>,
    ) : this(
        cpuArchitecture = cpuArchitecture,
        logicalCoreCount = logicalCoreCount,
        performanceCoreCount = performanceCoreCount,
        instructionSets = instructionSets.values.toSet(),
        backends = backends.values,
        memoryTopology = memoryTopology,
        evidence = evidence.values,
        collectionLimitExceeded = instructionSets.limitExceeded || backends.limitExceeded || evidence.limitExceeded,
    )

    internal fun withBackends(value: Collection<BackendCapability>): HardwareProfile {
        val snapshot = boundedCollectionSnapshot(value, RecommendationPolicyV1.MAX_BACKEND_CAPABILITIES)
        return HardwareProfile(
            cpuArchitecture = cpuArchitecture,
            logicalCoreCount = logicalCoreCount,
            performanceCoreCount = performanceCoreCount,
            instructionSets = instructionSets,
            backends = snapshot.values,
            memoryTopology = memoryTopology,
            evidence = evidence,
            collectionLimitExceeded = collectionLimitExceeded || snapshot.limitExceeded,
        )
    }

    internal fun revalidated(): HardwareProfile = validatedHardwareProfile(
        cpuArchitecture = cpuArchitecture,
        logicalCoreCountReading = logicalCoreCount,
        performanceCoreCountReading = performanceCoreCount,
        instructionSets = instructionSets,
        backends = backends,
        memoryTopology = memoryTopology,
        evidence = evidence,
    ).withCollectionLimitExceeded(collectionLimitExceeded)

    internal fun withCollectionLimitExceeded(value: Boolean): HardwareProfile = if (!value) {
        this
    } else {
        HardwareProfile(
            cpuArchitecture = cpuArchitecture,
            logicalCoreCount = logicalCoreCount,
            performanceCoreCount = performanceCoreCount,
            instructionSets = instructionSets,
            backends = backends,
            memoryTopology = memoryTopology,
            evidence = evidence,
            collectionLimitExceeded = true,
        )
    }
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
    val confidence: ResourcePoolConfidence,
    internal val collectionLimitExceeded: Boolean,
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
        confidence: ResourcePoolConfidence = ResourcePoolConfidence(),
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
        evidence = boundedCollectionSnapshot(evidence, RecommendationPolicyV1.MAX_EVIDENCE_ENTRIES),
        confidence = confidence,
    )

    private constructor(
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
        evidence: BoundedCollectionSnapshot<Evidence>,
        confidence: ResourcePoolConfidence,
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
        evidence = evidence.values,
        confidence = confidence,
        collectionLimitExceeded = evidence.limitExceeded,
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
        storageConfidence: Confidence?,
        additionalEvidence: Collection<Evidence>,
    ): ResourceSnapshot {
        val additional = boundedCollectionSnapshot(additionalEvidence, RecommendationPolicyV1.MAX_EVIDENCE_ENTRIES)
        val combined = boundedCollectionSnapshot(
            evidence + additional.values,
            RecommendationPolicyV1.MAX_EVIDENCE_ENTRIES,
        )
        return ResourceSnapshot(
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
            evidence = combined.values,
            confidence = confidence.copy(
                storage = storageConfidence.takeIf { storageBytes != null },
            ),
            collectionLimitExceeded = collectionLimitExceeded || additional.limitExceeded || combined.limitExceeded,
        )
    }

    internal fun withCollectionLimitExceeded(value: Boolean): ResourceSnapshot = if (!value) {
        this
    } else {
        ResourceSnapshot(
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
            evidence = evidence,
            confidence = confidence,
            collectionLimitExceeded = true,
        )
    }
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
    val budgetConfidence: ResourcePoolConfidence,
    internal val collectionLimitExceeded: Boolean,
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
        budgetConfidence: ResourcePoolConfidence = ResourcePoolConfidence(),
    ) : this(
        hardwareProfile = hardwareProfile,
        resources = resources,
        baseHostBudgetBytes = baseHostBudgetBytes,
        baseGpuBudgetBytes = baseGpuBudgetBytes,
        baseSharedBudgetBytes = baseSharedBudgetBytes,
        baseStorageBudgetBytes = baseStorageBudgetBytes,
        isFresh = isFresh,
        evidence = boundedCollectionSnapshot(evidence, RecommendationPolicyV1.MAX_EVIDENCE_ENTRIES),
        budgetConfidence = budgetConfidence,
    )

    private constructor(
        hardwareProfile: HardwareProfile,
        resources: ResourceSnapshot,
        baseHostBudgetBytes: Long?,
        baseGpuBudgetBytes: Long?,
        baseSharedBudgetBytes: Long?,
        baseStorageBudgetBytes: Long?,
        isFresh: Boolean,
        evidence: BoundedCollectionSnapshot<Evidence>,
        budgetConfidence: ResourcePoolConfidence,
    ) : this(
        hardwareProfile = hardwareProfile,
        resources = resources,
        baseHostBudgetBytes = baseHostBudgetBytes,
        baseGpuBudgetBytes = baseGpuBudgetBytes,
        baseSharedBudgetBytes = baseSharedBudgetBytes,
        baseStorageBudgetBytes = baseStorageBudgetBytes,
        isFresh = isFresh,
        evidence = evidence.values,
        budgetConfidence = budgetConfidence,
        collectionLimitExceeded = evidence.limitExceeded,
    )
}

internal fun fallbackPerformanceCoreCount(logicalCoreCount: Int): Int? =
    logicalCoreCount
        .takeIf { it in 1..MAX_LOGICAL_CORE_COUNT }
        ?.let { logical -> maxOf(1, logical / 2).coerceAtMost(logical) }

internal data class ValidatedCoreCount(
    val value: Int,
    val evidence: List<Evidence>,
)

internal fun validatedUnsignedCoreCount(
    reading: ULong,
    detail: String,
): ValidatedCoreCount = if (reading in 1uL..MAX_LOGICAL_CORE_COUNT.toULong()) {
    ValidatedCoreCount(reading.toInt(), emptyList())
} else {
    ValidatedCoreCount(
        value = 1,
        evidence = listOf(invalidCoreEvidence("$detail=$reading")),
    )
}

internal fun <T> withOwnedMachPort(
    port: UInt,
    deallocate: (UInt) -> Unit,
    block: (UInt) -> T,
): T = try {
    block(port)
} finally {
    deallocate(port)
}

internal fun validatedHardwareProfile(
    cpuArchitecture: String,
    logicalCoreCountReading: Int,
    performanceCoreCountReading: Int?,
    instructionSets: Collection<String>,
    backends: Collection<BackendCapability>,
    memoryTopology: MemoryTopology,
    evidence: Collection<Evidence> = emptyList(),
): HardwareProfile {
    val instructionSnapshot = boundedCollectionSnapshot(
        instructionSets,
        RecommendationPolicyV1.MAX_INSTRUCTION_SETS,
    )
    val backendSnapshot = boundedCollectionSnapshot(
        backends,
        RecommendationPolicyV1.MAX_BACKEND_CAPABILITIES,
    )
    val evidenceSnapshot = boundedCollectionSnapshot(
        evidence,
        RecommendationPolicyV1.MAX_EVIDENCE_ENTRIES,
    )
    val validationEvidence = evidenceSnapshot.values.toMutableList()
    val logicalCoreCountIsValid = logicalCoreCountReading in 1..MAX_LOGICAL_CORE_COUNT
    val logicalCoreCount = logicalCoreCountReading.takeIf { logicalCoreCountIsValid }
        ?: 1.also {
            validationEvidence += invalidCoreEvidence(
                "logical-core-count=$logicalCoreCountReading",
            )
        }
    val performanceCoreCount = performanceCoreCountReading
        ?.takeIf { logicalCoreCountIsValid && it in 1..logicalCoreCount }
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
        instructionSets = instructionSnapshot.values.filter { it.length <= 64 }.toSet(),
        backends = backendSnapshot.values,
        memoryTopology = memoryTopology,
        evidence = validationEvidence,
    ).withCollectionLimitExceeded(
        instructionSnapshot.limitExceeded || backendSnapshot.limitExceeded || evidenceSnapshot.limitExceeded,
    )
}

private fun invalidCoreEvidence(detail: String) = Evidence(
    reason = AssessmentReason.INVALID_CORE_COUNT,
    confidence = Confidence.LOW,
    detail = detail,
)
