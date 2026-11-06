package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.settings.AppSettings
import com.debanshu777.caraml.core.settings.KvQuantPreset
import com.debanshu777.caraml.core.download.diffusion.getModelSetup
import com.debanshu777.caraml.core.rating.DiffusionStepPolicy
import com.debanshu777.caraml.core.rating.DistilledHint
import com.debanshu777.caraml.core.rating.SdArchitectureClassifier
import com.debanshu777.caraml.sdk.plugin.diffusion.bridge.SampleMethod
import com.debanshu777.caraml.features.chat.domain.GenerationMode
import com.debanshu777.caraml.features.modelhub.presentation.search.ModelHubBrowseMode

class InstalledModelWorkloadFactory(
    private val fixedTextWorkload: LlmWorkloadConfig? = null,
    private val requireFullVulkanPlacement: Boolean = false,
) {
    init {
        require(!requireFullVulkanPlacement || fixedTextWorkload != null) { "Exact placement requires a fixed workload" }
        require(fixedTextWorkload == null || (
            !fixedTextWorkload.allowContextFallback && !fixedTextWorkload.allowBatchFallback &&
                !fixedTextWorkload.allowKvCacheFallback &&
                fixedTextWorkload.kvCacheSelection is KvCacheSelection.Explicit
            )) { "A fixed workload must request explicit KV types and disable fallback" }
    }
    fun create(
        descriptor: ModelDescriptor,
        expectedMode: GenerationMode,
        settings: AppSettings,
    ): WorkloadConfig? = when {
        descriptor is LlmModelDescriptor && expectedMode == GenerationMode.Text ->
            text(descriptor, settings.kvQuantPreset)
        descriptor is DiffusionModelDescriptor &&
            descriptor.mode == DiffusionMode.IMAGE && expectedMode == GenerationMode.Image ->
            installedImage(descriptor, settings.optimizationPriority)
        descriptor is DiffusionModelDescriptor &&
            descriptor.mode == DiffusionMode.VIDEO && expectedMode == GenerationMode.Video ->
            diffusion(DiffusionMode.VIDEO)
        else -> null
    }

    fun createForBrowse(mode: ModelHubBrowseMode): WorkloadConfig = when (mode) {
        ModelHubBrowseMode.LanguageModels -> text(contextLimit = null, preset = KvQuantPreset.AUTO)
        ModelHubBrowseMode.DiffusionImage -> diffusion(DiffusionMode.IMAGE)
        ModelHubBrowseMode.DiffusionVideo -> diffusion(DiffusionMode.VIDEO)
    }

    private fun text(descriptor: LlmModelDescriptor, preset: KvQuantPreset): LlmWorkloadConfig? {
        val limit = descriptor.contextLimit
        if (limit != null && limit < MIN_CONTEXT_TOKENS) return null
        fixedTextWorkload?.let { fixed ->
            // Request exact candidates before assessment/admission, never rewrite an admitted plan.
            val requested = if (requireFullVulkanPlacement || fixed.requestedFullVulkanLayerCount != null) {
                val derived = descriptor.validatedFullVulkanLayerCount() ?: return null
                if (fixed.requestedFullVulkanLayerCount != null && fixed.requestedFullVulkanLayerCount != derived) return null
                fixed.withFullVulkanPlacement(derived)
            } else fixed
            return requested.takeIf { limit == null || it.contextTokens <= limit }
        }
        return text(limit, preset, onnx = descriptor.format == ModelFormat.ONNX)
    }

    private fun text(contextLimit: Int?, preset: KvQuantPreset, onnx: Boolean = false): LlmWorkloadConfig {
        val contextTokens = minOf(contextLimit ?: DEFAULT_CONTEXT_TOKENS, DEFAULT_CONTEXT_TOKENS)
        val kv = if (onnx) KvPolicy(KvCacheSelection.Explicit(KvCacheType.F32, KvCacheType.F32), listOf(KvCacheType.F32), false)
            else preset.toKvPolicy()
        return LlmWorkloadConfig(
            userRequestedContextTokens = contextTokens,
            contextTokens = contextTokens,
            minimumContextTokens = MIN_CONTEXT_TOKENS,
            promptTokens = PROMPT_TOKENS,
            generationReserveTokens = GENERATION_RESERVE_TOKENS,
            batchSize = BATCH_SIZE,
            microBatchSize = MICRO_BATCH_SIZE,
            sequenceCount = 1,
            kvCacheSelection = kv.selection,
            allowContextFallback = true,
            allowBatchFallback = true,
            allowKvCacheFallback = kv.allowFallback,
            allowedKvCacheTypes = kv.allowedTypes,
            evidence = emptyList(),
        )
    }

    private fun installedImage(
        descriptor: DiffusionModelDescriptor,
        priority: OptimizationPriority,
    ): DiffusionWorkloadConfig {
        val params = getModelSetup(descriptor.repositoryId)?.recommendedParams
        val architecture = descriptor.architecture
            ?: SdArchitectureClassifier.classify(emptyList(), descriptor.repositoryId)
        val distilled = if (SdArchitectureClassifier.isDistilled(descriptor.repositoryId))
            DistilledHint.YES else DistilledHint.UNKNOWN
        val defaultSteps = params?.steps ?: DiffusionStepPolicy.recommend(
            architecture, SampleMethod.fromName(params?.samplingMethod), distilled,
        ).steps
        // Resolve model defaults before admission so the admitted workload and execution agree.
        return diffusion(
            DiffusionMode.IMAGE,
            width = params?.width ?: 512,
            height = params?.height ?: 512,
            steps = when (priority) {
                OptimizationPriority.SPEED_EFFICIENCY -> params?.speedSteps ?: defaultSteps
                OptimizationPriority.BALANCED -> params?.balancedSteps ?: defaultSteps
                else -> defaultSteps
            },
            offloadToCpu = params?.offloadToCpu ?: false,
            keepClipOnCpu = params?.clipOnCpu ?: false,
            keepVaeOnCpu = params?.keepVaeOnCpu ?: false,
        )
    }

    private fun diffusion(
        mode: DiffusionMode,
        width: Int = IMAGE_WIDTH,
        height: Int = if (mode == DiffusionMode.VIDEO) VIDEO_HEIGHT else IMAGE_HEIGHT,
        steps: Int = DIFFUSION_STEPS,
        offloadToCpu: Boolean = false,
        keepClipOnCpu: Boolean = false,
        keepVaeOnCpu: Boolean = false,
    ): DiffusionWorkloadConfig = DiffusionWorkloadConfig(
        mode = mode,
        width = width,
        height = height,
        minimumWidth = MIN_DIFFUSION_DIMENSION,
        minimumHeight = MIN_DIFFUSION_DIMENSION,
        frameCount = if (mode == DiffusionMode.VIDEO) VIDEO_FRAMES else 1,
        minimumFrameCount = 1,
        batchSize = 1,
        steps = steps,
        vaeTiling = false,
        offloadToCpu = offloadToCpu,
        keepClipOnCpu = keepClipOnCpu,
        keepVaeOnCpu = keepVaeOnCpu,
        maxVramBytes = null,
        layerStreaming = false,
        allowResolutionFallback = true,
        allowFrameCountFallback = mode == DiffusionMode.VIDEO,
        allowVaeTilingFallback = true,
        allowMaxVramFallback = true,
        allowLayerStreamingFallback = true,
        evidence = emptyList(),
    )

    private fun KvQuantPreset.toKvPolicy(): KvPolicy = when (this) {
        KvQuantPreset.AUTO -> KvPolicy(
            selection = KvCacheSelection.Auto,
            allowedTypes = listOf(KvCacheType.F16, KvCacheType.Q8_0, KvCacheType.Q4_0),
            allowFallback = true,
        )
        KvQuantPreset.Q4_F16 -> KvPolicy(
            selection = KvCacheSelection.Explicit(KvCacheType.Q4_0, KvCacheType.F16),
            allowedTypes = listOf(KvCacheType.Q4_0, KvCacheType.F16),
            allowFallback = false,
        )
        KvQuantPreset.Q8_Q8 -> KvPolicy(
            selection = KvCacheSelection.Explicit(KvCacheType.Q8_0, KvCacheType.Q8_0),
            allowedTypes = listOf(KvCacheType.Q8_0),
            allowFallback = false,
        )
        KvQuantPreset.F16_F16 -> KvPolicy(
            selection = KvCacheSelection.Explicit(KvCacheType.F16, KvCacheType.F16),
            allowedTypes = listOf(KvCacheType.F16),
            allowFallback = false,
        )
    }

    private data class KvPolicy(
        val selection: KvCacheSelection,
        val allowedTypes: List<KvCacheType>,
        val allowFallback: Boolean,
    )

    private companion object {
        const val DEFAULT_CONTEXT_TOKENS = 4_096
        const val MIN_CONTEXT_TOKENS = 512
        const val PROMPT_TOKENS = 256
        const val GENERATION_RESERVE_TOKENS = 256
        const val BATCH_SIZE = 256
        const val MICRO_BATCH_SIZE = 64
        const val IMAGE_WIDTH = 1_024
        const val IMAGE_HEIGHT = 1_024
        const val VIDEO_HEIGHT = 576
        const val VIDEO_FRAMES = 16
        const val MIN_DIFFUSION_DIMENSION = 512
        const val DIFFUSION_STEPS = 20
    }
}
