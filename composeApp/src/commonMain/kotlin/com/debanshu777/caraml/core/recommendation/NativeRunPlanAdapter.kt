package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.diffusionrunner.DiffusionModelConfig
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
            autoFit = !cpuOnly && plan.gpuLayerCount == null,
        )
    }

    fun toDiffusionExecutionConfig(
        plan: DiffusionRunPlan,
        base: DiffusionModelConfig,
    ): DiffusionExecutionConfig {
        requireValid(plan)
        require(plan.batchSize == 1) { "Unsupported diffusion execution configuration" }
        return DiffusionExecutionConfig(
            model = base.copy(
                offloadToCpu = plan.offloadToCpu,
                keepClipOnCpu = plan.keepClipOnCpu,
                keepVaeOnCpu = plan.keepVaeOnCpu,
                vaeTiling = plan.vaeTiling,
                maxVram = plan.maxVramBytes?.toDecimalGib().orEmpty(),
                streamLayers = plan.layerStreaming,
                autoFit = false,
            ),
            mode = plan.mode,
            width = plan.width,
            height = plan.height,
            frameCount = plan.frameCount,
            batchSize = plan.batchSize,
            steps = plan.steps,
            maxVramBytes = plan.maxVramBytes,
        )
    }

    private fun requireValid(plan: RunPlan) {
        require(validateRunPlan(plan) == null) { "Invalid execution configuration" }
    }
}

private val KvCacheType.nativeValue: Int
    get() = when (this) {
        KvCacheType.F16 -> 1
        KvCacheType.Q8_0 -> 8
        KvCacheType.Q4_0 -> 2
    }

private fun Long.toDecimalGib(): String = (toDouble() / GIB_BYTES.toDouble()).toString()

private const val GIB_BYTES = 1_073_741_824L
