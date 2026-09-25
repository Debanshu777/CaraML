package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.diffusionrunner.DiffusionModelConfig
import com.debanshu777.diffusionrunner.DiffusionRuntimeBackend
import com.debanshu777.runner.LlamaLazyMode
import com.debanshu777.runner.NativeRunnerConfig

data class DiffusionExecutionConfig(
    val model: DiffusionModelConfig,
    val mode: DiffusionMode,
    val width: Int,
    val height: Int,
    val frameCount: Int,
    val batchSize: Int,
    val steps: Int,
    val maxVramBytes: Long?,
)

object NativeRunPlanAdapter {
    fun toLlamaConfig(plan: LlmRunPlan, base: NativeRunnerConfig): NativeRunnerConfig {
        requireValid(plan)
        val cpuOnly = plan.backend == BackendKind.CPU
        return base.copy(
            nCtx = plan.contextTokens,
            nBatch = plan.batchSize,
            nUbatch = plan.microBatchSize,
            typeK = plan.keyCacheType.nativeValue,
            typeV = plan.valueCacheType.nativeValue,
            nGpuLayers = if (cpuOnly) 0 else plan.gpuLayerCount ?: -1,
            offloadKqv = !cpuOnly && base.offloadKqv,
            useMmap = plan.useMmap,
            lazyMode = if (plan.useMmap) LlamaLazyMode.AUTO else LlamaLazyMode.OFF,
            autoFit = !cpuOnly && plan.gpuLayerCount == null,
        )
    }

    fun toDiffusionExecutionConfig(
        plan: DiffusionRunPlan,
        base: DiffusionModelConfig,
    ): DiffusionExecutionConfig {
        requireValid(plan)
        require(plan.batchSize == 1) { "Unsupported diffusion execution configuration" }
        val nativeBudget = plan.maxVramBytes?.toNativeDiffusionBudget()
        return DiffusionExecutionConfig(
            model = base.copy(
                runtimeBackend = plan.backend.toDiffusionRuntimeBackend(),
                offloadToCpu = plan.offloadToCpu,
                keepClipOnCpu = plan.keepClipOnCpu,
                keepVaeOnCpu = plan.keepVaeOnCpu,
                vaeTiling = plan.vaeTiling,
                maxVram = nativeBudget?.gibText.orEmpty(),
                segmentedCompute = plan.segmentedCompute,
                prefetch = plan.prefetch,
                autoFit = false,
            ),
            mode = plan.mode,
            width = plan.width,
            height = plan.height,
            frameCount = plan.frameCount,
            batchSize = plan.batchSize,
            steps = plan.steps,
            maxVramBytes = nativeBudget?.bytes,
        )
    }

    private fun requireValid(plan: RunPlan) {
        require(validateRunPlan(plan) == null) { "Invalid execution configuration" }
    }
}

private fun BackendKind.toDiffusionRuntimeBackend(): DiffusionRuntimeBackend = when (this) {
    BackendKind.CPU -> DiffusionRuntimeBackend.CPU
    BackendKind.METAL -> DiffusionRuntimeBackend.METAL
    BackendKind.VULKAN -> DiffusionRuntimeBackend.VULKAN
    BackendKind.CUDA -> DiffusionRuntimeBackend.CUDA
    BackendKind.OTHER -> throw IllegalArgumentException("Unsupported diffusion runtime backend")
}

private val KvCacheType.nativeValue: Int
    get() = when (this) {
        KvCacheType.F16 -> 1
        KvCacheType.Q8_0 -> 8
        KvCacheType.Q4_0 -> 2
    }

internal data class NativeDiffusionBudget(
    val gibText: String,
    val bytes: Long,
)

internal fun Long.toNativeDiffusionBudget(): NativeDiffusionBudget {
    require(this > 0L) { "Diffusion VRAM budget must be positive" }
    var gib = (toDouble() / GIB_BYTES.toDouble()).toFloat()
    require(gib.isFinite() && gib > 0f) { "Diffusion VRAM budget is not representable" }
    var effectiveBytes = (gib.toDouble() * GIB_BYTES.toDouble()).toLong()
    while (effectiveBytes > this) {
        gib = Float.fromBits(gib.toRawBits() - 1)
        effectiveBytes = (gib.toDouble() * GIB_BYTES.toDouble()).toLong()
    }
    require(effectiveBytes in 1..this) { "Diffusion VRAM budget is not representable" }
    return NativeDiffusionBudget(gibText = gib.toString(), bytes = effectiveBytes)
}

private const val GIB_BYTES = 1_073_741_824L
