package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.MemoryTopology
import com.debanshu777.diffusionrunner.DiffusionModelConfig
import com.debanshu777.diffusionrunner.DiffusionRuntimeBackend
import com.debanshu777.runner.LlamaLazyMode
import com.debanshu777.runner.NativeRunnerConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
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
        assertEquals(LlamaLazyMode.OFF, mapped.lazyMode)
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
        assertEquals(
            LlamaLazyMode.AUTO,
            NativeRunPlanAdapter.toLlamaConfig(automatic, NativeRunnerConfig()).lazyMode,
        )
        assertEquals(-1, NativeRunPlanAdapter.toLlamaConfig(automatic, NativeRunnerConfig()).nGpuLayers)
        assertFalse(NativeRunPlanAdapter.toLlamaConfig(cpu, NativeRunnerConfig()).autoFit)
        assertEquals(0, NativeRunPlanAdapter.toLlamaConfig(cpu, NativeRunnerConfig()).nGpuLayers)
    }

    @Test
    fun cpuSaferPlanDisablesEveryGpuOnlyNativeFlag() {
        val cpu = llmPlan(gpuLayers = 0, backend = BackendKind.CPU)

        val mapped = NativeRunPlanAdapter.toLlamaConfig(
            cpu,
            NativeRunnerConfig(nGpuLayers = -1, offloadKqv = true, autoFit = true),
        )

        assertEquals(0, mapped.nGpuLayers)
        assertFalse(mapped.offloadKqv)
        assertFalse(mapped.autoFit)
    }

    @Test
    fun gpuPlanPreservesExplicitlyDisabledKqvOffload() {
        val gpu = llmPlan(gpuLayers = 12, backend = BackendKind.VULKAN)

        val mapped = NativeRunPlanAdapter.toLlamaConfig(
            gpu,
            NativeRunnerConfig(offloadKqv = false),
        )

        assertFalse(mapped.offloadKqv)
    }

    @Test
    fun multipleLlmSequencesAreRejectedUntilTheNativeAbiCanCarryThem() {
        val unsupported = LlmRunPlan(
            contextTokens = 4_096,
            batchSize = 256,
            microBatchSize = 128,
            sequenceCount = 2,
            keyCacheType = KvCacheType.F16,
            valueCacheType = KvCacheType.F16,
            backend = BackendKind.CPU,
            memoryTopology = MemoryTopology.UNKNOWN,
            gpuLayerCount = 0,
            compromises = emptyList(),
        )

        assertFailsWith<IllegalArgumentException> {
            NativeRunPlanAdapter.toLlamaConfig(unsupported, NativeRunnerConfig())
        }
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
            maxVramBytes = 3_435_973_837L,
            segmentedCompute = true,
            prefetch = true,
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
        assertTrue(mapped.model.segmentedCompute)
        assertTrue(mapped.model.prefetch)
        assertFalse(mapped.model.autoFit)
        assertEquals("3.1999998", mapped.model.maxVram)
        assertEquals(3_435_973_632L, mapped.maxVramBytes)
        assertTrue(assertNotNull(mapped.maxVramBytes) <= assertNotNull(plan.maxVramBytes))
        assertEquals(768, mapped.width)
        assertEquals(448, mapped.height)
        assertEquals(24, mapped.frameCount)
        assertEquals(1, mapped.batchSize)
        assertEquals(31, mapped.steps)
        assertEquals(DiffusionMode.VIDEO, mapped.mode)
    }

    @Test
    fun cpuDiffusionPlanPinsTheNativeRuntimeToCpu() {
        val plan = diffusionPlan(backend = BackendKind.CPU)

        val mapped = NativeRunPlanAdapter.toDiffusionExecutionConfig(
            plan,
            DiffusionModelConfig(
                modelPath = "/private/model.gguf",
                runtimeBackend = DiffusionRuntimeBackend.VULKAN,
            ),
        )

        assertEquals(DiffusionRuntimeBackend.CPU, mapped.model.runtimeBackend)
    }

    @Test
    fun acceleratedDiffusionPlanPinsTheExactNativeBackendKind() {
        val expected = mapOf(
            BackendKind.METAL to DiffusionRuntimeBackend.METAL,
            BackendKind.VULKAN to DiffusionRuntimeBackend.VULKAN,
            BackendKind.CUDA to DiffusionRuntimeBackend.CUDA,
        )

        expected.forEach { (backend, nativeBackend) ->
            val mapped = NativeRunPlanAdapter.toDiffusionExecutionConfig(
                diffusionPlan(backend),
                DiffusionModelConfig(modelPath = "/private/model.gguf"),
            )

            assertEquals(nativeBackend, mapped.model.runtimeBackend)
        }
    }

    @Test
    fun unrepresentableDiffusionBackendFailsClosed() {
        assertFailsWith<IllegalArgumentException> {
            NativeRunPlanAdapter.toDiffusionExecutionConfig(
                diffusionPlan(backend = BackendKind.OTHER),
                DiffusionModelConfig(modelPath = "/private/model.gguf"),
            )
        }
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

    private fun diffusionPlan(backend: BackendKind) = DiffusionRunPlan(
        mode = DiffusionMode.IMAGE,
        width = 512,
        height = 512,
        frameCount = 1,
        batchSize = 1,
        steps = 20,
        vaeTiling = false,
        offloadToCpu = backend == BackendKind.CPU,
        keepClipOnCpu = backend == BackendKind.CPU,
        keepVaeOnCpu = backend == BackendKind.CPU,
        maxVramBytes = null,
        segmentedCompute = false,
        requiresUserAcceptance = false,
        backend = backend,
        memoryTopology = if (backend == BackendKind.CPU) MemoryTopology.UNKNOWN else MemoryTopology.DISCRETE,
        compromises = emptyList(),
    )
}
