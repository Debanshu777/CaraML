package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.MemoryTopology

object WorkloadLimits {
    const val MAX_BATCH_SIZE: Int = 65_536
    const val MAX_SEQUENCE_COUNT: Int = 1_024
    const val DEFAULT_MIN_LLM_CONTEXT_TOKENS: Int = 512
}

enum class KvCacheType {
    F16,
    Q8_0,
    Q4_0,
}

sealed interface KvCacheSelection {
    data object Auto : KvCacheSelection

    data class Explicit(
        val keyType: KvCacheType,
        val valueType: KvCacheType,
    ) : KvCacheSelection
}

sealed interface WorkloadConfig {
    val evidence: List<Evidence>
}

data class LlmWorkloadRequest(
    val contextTokens: Int,
    val minimumContextTokens: Int? = null,
    val promptTokens: Int,
    val generationReserveTokens: Int,
    val batchSize: Int,
    val microBatchSize: Int,
    val sequenceCount: Int = 1,
    val kvCacheSelection: KvCacheSelection = KvCacheSelection.Auto,
)

@ConsistentCopyVisibility
data class PlanningSettings private constructor(
    val engineMaxContextTokens: Int,
    val engineMaxBatchSize: Int,
    val engineMaxMicroBatchSize: Int,
    val engineMaxSequenceCount: Int,
    val allowContextFallback: Boolean,
    val allowBatchFallback: Boolean,
    val allowKvCacheFallback: Boolean,
    val allowedKvCacheTypes: List<KvCacheType>,
    val backend: BackendKind,
    val memoryTopology: MemoryTopology,
    val gpuLayerCount: Int?,
) {
    constructor(
        engineMaxContextTokens: Int,
        engineMaxBatchSize: Int,
        engineMaxMicroBatchSize: Int,
        engineMaxSequenceCount: Int,
        allowContextFallback: Boolean,
        allowBatchFallback: Boolean,
        allowKvCacheFallback: Boolean,
        allowedKvCacheTypes: Collection<KvCacheType>,
        backend: BackendKind,
        memoryTopology: MemoryTopology,
        gpuLayerCount: Int?,
    ) : this(
        engineMaxContextTokens = engineMaxContextTokens,
        engineMaxBatchSize = engineMaxBatchSize,
        engineMaxMicroBatchSize = engineMaxMicroBatchSize,
        engineMaxSequenceCount = engineMaxSequenceCount,
        allowContextFallback = allowContextFallback,
        allowBatchFallback = allowBatchFallback,
        allowKvCacheFallback = allowKvCacheFallback,
        allowedKvCacheTypes = allowedKvCacheTypes.distinct(),
        backend = backend,
        memoryTopology = memoryTopology,
        gpuLayerCount = gpuLayerCount,
    )
}

@ConsistentCopyVisibility
data class LlmWorkloadConfig private constructor(
    val userRequestedContextTokens: Int,
    val contextTokens: Int,
    val minimumContextTokens: Int,
    val promptTokens: Int,
    val generationReserveTokens: Int,
    val batchSize: Int,
    val microBatchSize: Int,
    val sequenceCount: Int,
    val kvCacheSelection: KvCacheSelection,
    val allowContextFallback: Boolean,
    val allowBatchFallback: Boolean,
    val allowKvCacheFallback: Boolean,
    val allowedKvCacheTypes: List<KvCacheType>,
    override val evidence: List<Evidence>,
) : WorkloadConfig {
    constructor(
        userRequestedContextTokens: Int,
        contextTokens: Int,
        minimumContextTokens: Int,
        promptTokens: Int,
        generationReserveTokens: Int,
        batchSize: Int,
        microBatchSize: Int,
        sequenceCount: Int,
        kvCacheSelection: KvCacheSelection,
        allowContextFallback: Boolean,
        allowBatchFallback: Boolean,
        allowKvCacheFallback: Boolean,
        allowedKvCacheTypes: Collection<KvCacheType>,
        evidence: Collection<Evidence>,
    ) : this(
        userRequestedContextTokens = userRequestedContextTokens,
        contextTokens = contextTokens,
        minimumContextTokens = minimumContextTokens,
        promptTokens = promptTokens,
        generationReserveTokens = generationReserveTokens,
        batchSize = batchSize,
        microBatchSize = microBatchSize,
        sequenceCount = sequenceCount,
        kvCacheSelection = kvCacheSelection,
        allowContextFallback = allowContextFallback,
        allowBatchFallback = allowBatchFallback,
        allowKvCacheFallback = allowKvCacheFallback,
        allowedKvCacheTypes = allowedKvCacheTypes.distinct(),
        evidence = evidence.toList(),
    )
}

sealed interface WorkloadConfigResult {
    @ConsistentCopyVisibility
    data class Ready private constructor(
        val workload: WorkloadConfig,
        val evidence: List<Evidence>,
    ) : WorkloadConfigResult {
        constructor(workload: WorkloadConfig, evidence: Collection<Evidence>) :
            this(workload, evidence.toList())
    }

    @ConsistentCopyVisibility
    data class Invalid private constructor(
        val reasons: List<AssessmentReason>,
        val evidence: List<Evidence>,
    ) : WorkloadConfigResult {
        constructor(reasons: Collection<AssessmentReason>, evidence: Collection<Evidence>) :
            this(reasons.toList(), evidence.toList())
    }
}
