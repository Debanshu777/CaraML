package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.MemoryTopology
import com.debanshu777.diffusionrunner.DiffusionModelConfig
import com.debanshu777.runner.NativeRunnerConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeRunPlanAdapterTest {
    @Test
    fun llamaPlanMapsEveryExecutableFieldWithoutFallingBackToProfileDefaults() {
        val plan = LlmRunPlan(
            contextTokens = 8_192,
            batchSize = 384,
            microBatchSize = 96,
            sequenceCount = 1,
            keyCacheType = KvCacheType.Q4_0,
            valueCacheType = KvCacheType.Q8_0,
            backend = BackendKind.VULKAN,
            memoryTopology = MemoryTopology.DISCRETE,
            gpuLayerCount = 17,
            useMmap = false,
            compromises = listOf(
                RunPlanCompromise.CONTEXT_REDUCED,
                RunPlanCompromise.KV_CACHE_REDUCED,
            ),
        )
        val base = NativeRunnerConfig(
            nCtx = 999,
            nBatch = 999,
            nUbatch = 999,
            typeK = 1,
            typeV = 1,
            nGpuLayers = -1,
            useMmap = true,
            autoFit = true,
            nThreads = 7,
            nThreadsBatch = 5,
            temperature = 0.7f,
        )

        val mapped = NativeRunPlanAdapter.toLlamaConfig(plan, base)

        assertEquals(8_192, mapped.nCtx)
        assertEquals(384, mapped.nBatch)
        assertEquals(96, mapped.nUbatch)
        assertEquals(2, mapped.typeK)
        assertEquals(8, mapped.typeV)
        assertEquals(17, mapped.nGpuLayers)
        assertFalse(mapped.useMmap)
        assertFalse(mapped.autoFit)
        assertEquals(7, mapped.nThreads)
        assertEquals(5, mapped.nThreadsBatch)
        assertEquals(0.7f, mapped.temperature)
    }

    @Test
    fun nullGpuLayerCountIsTheOnlyAutoFitMapping() {
        val automatic = llmPlan(gpuLayers = null, backend = BackendKind.METAL)
        val cpu = llmPlan(gpuLayers = 0, backend = BackendKind.CPU)

        assertTrue(NativeRunPlanAdapter.toLlamaConfig(automatic, NativeRunnerConfig()).autoFit)
        assertEquals(-1, NativeRunPlanAdapter.toLlamaConfig(automatic, NativeRunnerConfig()).nGpuLayers)
        assertFalse(NativeRunPlanAdapter.toLlamaConfig(cpu, NativeRunnerConfig()).autoFit)
        assertEquals(0, NativeRunPlanAdapter.toLlamaConfig(cpu, NativeRunnerConfig()).nGpuLayers)
    }

    @Test
    fun diffusionPlanMapsLoadAndNumericImageVideoFieldsLosslessly() {
        val plan = DiffusionRunPlan(
            mode = DiffusionMode.VIDEO,
            width = 768,
            height = 448,
            frameCount = 24,
            batchSize = 1,
            steps = 31,
            vaeTiling = true,
            offloadToCpu = true,
            keepClipOnCpu = true,
            keepVaeOnCpu = false,
            maxVramBytes = 3_221_225_472L,
            layerStreaming = true,
            requiresUserAcceptance = true,
            backend = BackendKind.VULKAN,
            memoryTopology = MemoryTopology.DISCRETE,
            compromises = listOf(
                DiffusionPlanCompromise.LOWER_RESOLUTION,
                DiffusionPlanCompromise.LAYER_STREAMING,
            ),
        )
        val base = DiffusionModelConfig(
            modelPath = "/private/model.gguf",
            vaePath = "/private/vae.safetensors",
            enableMmap = true,
            autoFit = true,
        )

        val mapped = NativeRunPlanAdapter.toDiffusionExecutionConfig(plan, base)

        assertEquals(base.modelPath, mapped.model.modelPath)
        assertEquals(base.vaePath, mapped.model.vaePath)
        assertTrue(mapped.model.offloadToCpu)
        assertTrue(mapped.model.keepClipOnCpu)
        assertFalse(mapped.model.keepVaeOnCpu)
        assertTrue(mapped.model.vaeTiling)
        assertTrue(mapped.model.streamLayers)
        assertFalse(mapped.model.autoFit)
        assertEquals(plan.maxVramBytes, mapped.maxVramBytes)
        assertEquals(768, mapped.width)
        assertEquals(448, mapped.height)
        assertEquals(24, mapped.frameCount)
        assertEquals(1, mapped.batchSize)
        assertEquals(31, mapped.steps)
        assertEquals(DiffusionMode.VIDEO, mapped.mode)
    }

    private fun llmPlan(gpuLayers: Int?, backend: BackendKind) = LlmRunPlan(
        contextTokens = 4_096,
        batchSize = 256,
        microBatchSize = 128,
        sequenceCount = 1,
        keyCacheType = KvCacheType.F16,
        valueCacheType = KvCacheType.F16,
        backend = backend,
        memoryTopology = if (backend == BackendKind.CPU) MemoryTopology.UNKNOWN else MemoryTopology.UNIFIED,
        gpuLayerCount = gpuLayers,
        compromises = emptyList(),
    )
}
