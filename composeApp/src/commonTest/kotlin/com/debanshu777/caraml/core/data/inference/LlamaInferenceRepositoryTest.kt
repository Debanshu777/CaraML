package com.debanshu777.caraml.core.data.inference

import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.MemoryTopology
import com.debanshu777.caraml.core.recommendation.KvCacheType
import com.debanshu777.caraml.core.recommendation.LlmRunPlan
import com.debanshu777.runner.NativeRunnerConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class LlamaInferenceRepositoryTest {
    @Test
    fun hybridSsmGpuPlanIsRejectedInsteadOfMutatingTheAdmittedPlan() {
        val result = exactLlamaRunnerConfig(
            architecture = "qwen35",
            plan = plan(backend = BackendKind.VULKAN, gpuLayers = 16),
            base = NativeRunnerConfig(offloadKqv = false, nGpuLayers = 0),
        )

        assertNull(result)
    }

    @Test
    fun admittedCpuSaferPlanProducesCpuOnlyExactConfig() {
        val result = requireNotNull(
            exactLlamaRunnerConfig(
                architecture = "qwen35",
                plan = plan(backend = BackendKind.CPU, gpuLayers = 0),
                base = NativeRunnerConfig(offloadKqv = true, nGpuLayers = -1),
            ),
        )

        assertEquals(0, result.nGpuLayers)
        assertFalse(result.offloadKqv)
        assertFalse(result.autoFit)
    }

    private fun plan(backend: BackendKind, gpuLayers: Int?) = LlmRunPlan(
        contextTokens = 4_096,
        batchSize = 256,
        microBatchSize = 64,
        sequenceCount = 1,
        keyCacheType = KvCacheType.Q8_0,
        valueCacheType = KvCacheType.Q8_0,
        backend = backend,
        memoryTopology = if (backend == BackendKind.CPU) MemoryTopology.UNKNOWN else MemoryTopology.UNIFIED,
        gpuLayerCount = gpuLayers,
        compromises = emptyList(),
    )
}
