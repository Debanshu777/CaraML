package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.MemoryTopology

enum class RunPlanCompromise {
    CONTEXT_REDUCED,
    BATCH_REDUCED,
    MICRO_BATCH_REDUCED,
    KV_CACHE_REDUCED,
}

sealed interface RunPlan : PlanReference {
    val backend: BackendKind
    val memoryTopology: MemoryTopology
    val compromises: List<RunPlanCompromise>
}

@ConsistentCopyVisibility
data class LlmRunPlan private constructor(
    val contextTokens: Int,
    val batchSize: Int,
    val microBatchSize: Int,
    val sequenceCount: Int,
    val keyCacheType: KvCacheType,
    val valueCacheType: KvCacheType,
    override val backend: BackendKind,
    override val memoryTopology: MemoryTopology,
    val gpuLayerCount: Int?,
    override val compromises: List<RunPlanCompromise>,
) : RunPlan {
    constructor(
        contextTokens: Int,
        batchSize: Int,
        microBatchSize: Int,
        sequenceCount: Int,
        keyCacheType: KvCacheType,
        valueCacheType: KvCacheType,
        backend: BackendKind,
        memoryTopology: MemoryTopology,
        gpuLayerCount: Int?,
        compromises: Collection<RunPlanCompromise>,
    ) : this(
        contextTokens = contextTokens,
        batchSize = batchSize,
        microBatchSize = microBatchSize,
        sequenceCount = sequenceCount,
        keyCacheType = keyCacheType,
        valueCacheType = valueCacheType,
        backend = backend,
        memoryTopology = memoryTopology,
        gpuLayerCount = gpuLayerCount,
        compromises = compromises.distinct(),
    )

    override val stableKey: String
        get() = buildString {
            append("llm:")
            append(contextTokens).append(':')
            append(batchSize).append(':')
            append(microBatchSize).append(':')
            append(sequenceCount).append(':')
            append(keyCacheType.name).append(':')
            append(valueCacheType.name).append(':')
            append(backend.name).append(':')
            append(memoryTopology.name).append(':')
            append(gpuLayerCount?.toString() ?: "auto")
        }
}
