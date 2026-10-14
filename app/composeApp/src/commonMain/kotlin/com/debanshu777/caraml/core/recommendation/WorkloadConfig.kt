package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.MemoryTopology

object WorkloadLimits {
    const val MAX_BATCH_SIZE: Int = 65_536
    const val MAX_SEQUENCE_COUNT: Int = 1_024
    const val DEFAULT_MIN_LLM_CONTEXT_TOKENS: Int = 512
    const val MAX_DIFFUSION_FRAMES: Int = 4_096
    const val MAX_DIFFUSION_STEPS: Int = 10_000
    const val DEFAULT_IMAGE_DIMENSION_MULTIPLE: Int = 64
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
    val engineImageDimensionMultiple: Int,
    val engineMaxImageDimension: Int,
    val engineMaxDiffusionFrames: Int,
    val engineMaxDiffusionSteps: Int,
    val supportsVaeTiling: Boolean,
    val supportsMaxVram: Boolean,
    val supportsLayerStreaming: Boolean,
    val maxVramBytes: Long?,
    val prefetchHeadroomBytes: Long?,
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
        engineImageDimensionMultiple: Int = WorkloadLimits.DEFAULT_IMAGE_DIMENSION_MULTIPLE,
        engineMaxImageDimension: Int = DescriptorLimits.MAX_IMAGE_DIMENSION,
        engineMaxDiffusionFrames: Int = WorkloadLimits.MAX_DIFFUSION_FRAMES,
        engineMaxDiffusionSteps: Int = WorkloadLimits.MAX_DIFFUSION_STEPS,
        supportsVaeTiling: Boolean = false,
        supportsMaxVram: Boolean = false,
        supportsLayerStreaming: Boolean = false,
        maxVramBytes: Long? = null,
        prefetchHeadroomBytes: Long? = null,
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
        engineImageDimensionMultiple = engineImageDimensionMultiple,
        engineMaxImageDimension = engineMaxImageDimension,
        engineMaxDiffusionFrames = engineMaxDiffusionFrames,
        engineMaxDiffusionSteps = engineMaxDiffusionSteps,
        supportsVaeTiling = supportsVaeTiling,
        supportsMaxVram = supportsMaxVram,
        supportsLayerStreaming = supportsLayerStreaming,
        maxVramBytes = maxVramBytes,
        prefetchHeadroomBytes = prefetchHeadroomBytes,
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

@ConsistentCopyVisibility
data class DiffusionWorkloadConfig private constructor(
    val mode: DiffusionMode,
    val width: Int,
    val height: Int,
    val minimumWidth: Int,
    val minimumHeight: Int,
    val frameCount: Int,
    val minimumFrameCount: Int,
    val batchSize: Int,
    val steps: Int,
    val vaeTiling: Boolean,
    val offloadToCpu: Boolean,
    val keepClipOnCpu: Boolean,
    val keepVaeOnCpu: Boolean,
    val maxVramBytes: Long?,
    val layerStreaming: Boolean,
    val allowResolutionFallback: Boolean,
    val allowFrameCountFallback: Boolean,
    val allowVaeTilingFallback: Boolean,
    val allowMaxVramFallback: Boolean,
    val allowLayerStreamingFallback: Boolean,
    override val evidence: List<Evidence>,
) : WorkloadConfig {
    constructor(
        mode: DiffusionMode,
        width: Int,
        height: Int,
        minimumWidth: Int,
        minimumHeight: Int,
        frameCount: Int,
        minimumFrameCount: Int,
        batchSize: Int,
        steps: Int,
        vaeTiling: Boolean,
        offloadToCpu: Boolean,
        keepClipOnCpu: Boolean,
        keepVaeOnCpu: Boolean,
        maxVramBytes: Long?,
        layerStreaming: Boolean,
        allowResolutionFallback: Boolean,
        allowFrameCountFallback: Boolean,
        allowVaeTilingFallback: Boolean,
        allowMaxVramFallback: Boolean,
        allowLayerStreamingFallback: Boolean,
        evidence: Collection<Evidence>,
    ) : this(
        mode = mode,
        width = width,
        height = height,
        minimumWidth = minimumWidth,
        minimumHeight = minimumHeight,
        frameCount = frameCount,
        minimumFrameCount = minimumFrameCount,
        batchSize = batchSize,
        steps = steps,
        vaeTiling = vaeTiling,
        offloadToCpu = offloadToCpu,
        keepClipOnCpu = keepClipOnCpu,
        keepVaeOnCpu = keepVaeOnCpu,
        maxVramBytes = maxVramBytes,
        layerStreaming = layerStreaming,
        allowResolutionFallback = allowResolutionFallback,
        allowFrameCountFallback = allowFrameCountFallback,
        allowVaeTilingFallback = allowVaeTilingFallback,
        allowMaxVramFallback = allowMaxVramFallback,
        allowLayerStreamingFallback = allowLayerStreamingFallback,
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
