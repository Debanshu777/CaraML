package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.MemoryTopology

sealed interface PlanCompromise

enum class RunPlanCompromise : PlanCompromise {
    CONTEXT_REDUCED,
    BATCH_REDUCED,
    MICRO_BATCH_REDUCED,
    KV_CACHE_REDUCED,
}

enum class DiffusionPlanCompromise : PlanCompromise {
    LOWER_RESOLUTION,
    FRAME_COUNT_REDUCED,
    VAE_TILING,
    CPU_OFFLOAD,
    MAX_VRAM_LIMIT,
    LAYER_STREAMING,
}

sealed interface RunPlan : PlanReference {
    val backend: BackendKind
    val memoryTopology: MemoryTopology
    val compromises: List<PlanCompromise>
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

@ConsistentCopyVisibility
data class DiffusionRunPlan private constructor(
    val mode: DiffusionMode,
    val width: Int,
    val height: Int,
    val frameCount: Int,
    val batchSize: Int,
    val steps: Int,
    val vaeTiling: Boolean,
    val offloadToCpu: Boolean,
    val keepClipOnCpu: Boolean,
    val keepVaeOnCpu: Boolean,
    val maxVramBytes: Long?,
    val layerStreaming: Boolean,
    val requiresUserAcceptance: Boolean,
    override val backend: BackendKind,
    override val memoryTopology: MemoryTopology,
    override val compromises: List<DiffusionPlanCompromise>,
) : RunPlan {
    constructor(
        mode: DiffusionMode,
        width: Int,
        height: Int,
        frameCount: Int,
        batchSize: Int,
        steps: Int,
        vaeTiling: Boolean,
        offloadToCpu: Boolean,
        keepClipOnCpu: Boolean,
        keepVaeOnCpu: Boolean,
        maxVramBytes: Long?,
        layerStreaming: Boolean,
        requiresUserAcceptance: Boolean,
        backend: BackendKind,
        memoryTopology: MemoryTopology,
        compromises: Collection<DiffusionPlanCompromise>,
    ) : this(
        mode = mode,
        width = width,
        height = height,
        frameCount = frameCount,
        batchSize = batchSize,
        steps = steps,
        vaeTiling = vaeTiling,
        offloadToCpu = offloadToCpu,
        keepClipOnCpu = keepClipOnCpu,
        keepVaeOnCpu = keepVaeOnCpu,
        maxVramBytes = maxVramBytes,
        layerStreaming = layerStreaming,
        requiresUserAcceptance = requiresUserAcceptance,
        backend = backend,
        memoryTopology = memoryTopology,
        compromises = compromises.distinct(),
    )

    override val stableKey: String
        get() = buildString {
            append("diffusion:")
            append(mode.name).append(':')
            append(width).append(':')
            append(height).append(':')
            append(frameCount).append(':')
            append(batchSize).append(':')
            append(steps).append(':')
            append(vaeTiling).append(':')
            append(offloadToCpu).append(':')
            append(keepClipOnCpu).append(':')
            append(keepVaeOnCpu).append(':')
            append(maxVramBytes?.toString() ?: "none").append(':')
            append(layerStreaming).append(':')
            append(backend.name).append(':')
            append(memoryTopology.name)
        }
}
