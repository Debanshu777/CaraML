package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.MemoryTopology
import com.debanshu777.caraml.sdk.plugin.llama.bridge.LlamaFitReport
import com.debanshu777.caraml.sdk.plugin.llama.bridge.LlamaPreflightMemoryPool
import com.debanshu777.caraml.sdk.plugin.llama.bridge.LlamaMemoryPoolKind
import kotlin.test.Test
import kotlin.test.assertEquals

class NativeMetadataLoadPreflightTest {
    @Test
    fun unifiedVulkanChargesBothPoolsAndRequiresVerifiedBackendAndExactConfiguration() {
        fun gpuPlanFor(topology: MemoryTopology = MemoryTopology.UNIFIED) = LlmRunPlan(
            4096, 256, 64, 1, KvCacheType.F16, KvCacheType.F16,
            BackendKind.VULKAN, topology, null, compromises = emptyList())
        val gpuPlan = gpuPlanFor()
        val pools = listOf(
            LlamaPreflightMemoryPool(LlamaMemoryPoolKind.HOST, 1, 100, 50, 25, 2000, 2000),
            LlamaPreflightMemoryPool(LlamaMemoryPoolKind.INTEGRATED_GPU, 0, 600, 200, 100, 2000, 2000),
        )
        val fit = LlamaFitReport(4096, 17, pools)
        fun snapshot(budget: Long = 1075, verified: Boolean = true) = task6Snapshot(
            sharedBudget = budget, topology = MemoryTopology.UNIFIED,
            backends = listOf(task6Backend(BackendKind.VULKAN,
                if (verified) com.debanshu777.caraml.core.platform.BackendStatus.AVAILABLE
                else com.debanshu777.caraml.core.platform.BackendStatus.UNKNOWN)),
        )
        assertEquals(NativeLoadPreflight.Fit, classifyNativeMetadataPreflight(fit, gpuPlan, snapshot()))
        assertEquals(NativeLoadPreflight.NoFit, classifyNativeMetadataPreflight(fit, gpuPlan, snapshot(1074)))
        assertEquals(NativeLoadPreflight.Unavailable, classifyNativeMetadataPreflight(fit, gpuPlan, snapshot(verified = false)))
        assertEquals(NativeLoadPreflight.Invalid, classifyNativeMetadataPreflight(fit.copy(fittedGpuLayers = 0), gpuPlan, snapshot()))
        assertEquals(NativeLoadPreflight.Invalid, classifyNativeMetadataPreflight(fit.copy(fittedContextTokens = 8192), gpuPlan, snapshot()))
        assertEquals(NativeLoadPreflight.Invalid, classifyNativeMetadataPreflight(fit.copy(memoryPools = listOf(pools[0], pools[1].copy(ordinal = 1))), gpuPlan, snapshot()))
        assertEquals(NativeLoadPreflight.NoFit, classifyNativeMetadataPreflight(fit.copy(memoryPools = listOf(pools[0], pools[1].copy(modelBytes = Long.MAX_VALUE))), gpuPlan, snapshot()))
        assertEquals(NativeLoadPreflight.Invalid, classifyNativeMetadataPreflight(fit, gpuPlanFor(MemoryTopology.DISCRETE), snapshot()))
        val ambiguous = task6Snapshot(sharedBudget = 1075, topology = MemoryTopology.UNIFIED,
            backends = listOf(task6Backend(BackendKind.VULKAN), task6Backend(BackendKind.METAL)))
        assertEquals(NativeLoadPreflight.Unavailable, classifyNativeMetadataPreflight(fit, gpuPlan, ambiguous))
    }

    private val plan = LlmRunPlan(4096, 256, 64, 1, KvCacheType.F16, KvCacheType.F16,
        BackendKind.CPU, MemoryTopology.UNKNOWN, 0, compromises = emptyList())
    private fun report(model: Long = 600, context: Long = 200, compute: Long = 100, free: Long = 2000) =
        LlamaFitReport(4096, 0, listOf(LlamaPreflightMemoryPool(
            LlamaMemoryPoolKind.HOST, 0, model, context, compute, free, 2000)))

    @Test
    fun nativeAllocationsMustFitCurrentHostBudgetWithoutOverflow() {
        assertEquals(NativeLoadPreflight.Fit, classifyNativeMetadataPreflight(report(), plan, task6Snapshot(hostBudget = 900)))
        assertEquals(NativeLoadPreflight.NoFit, classifyNativeMetadataPreflight(report(), plan, task6Snapshot(hostBudget = 899)))
        assertEquals(NativeLoadPreflight.NoFit, classifyNativeMetadataPreflight(report(free = 899), plan, task6Snapshot(hostBudget = 1000)))
        assertEquals(NativeLoadPreflight.NoFit, classifyNativeMetadataPreflight(report(model = Long.MAX_VALUE), plan, task6Snapshot(hostBudget = 1000)))
    }

    @Test
    fun staleOrMissingResourcesAndChangedNativeConfigurationCannotBeAdmitted() {
        assertEquals(NativeLoadPreflight.Unavailable, classifyNativeMetadataPreflight(report(), plan, task6Snapshot(hostBudget = null)))
        assertEquals(NativeLoadPreflight.Unavailable, classifyNativeMetadataPreflight(report(), plan, task6Snapshot(isFresh = false)))
        assertEquals(NativeLoadPreflight.Invalid, classifyNativeMetadataPreflight(report().copy(fittedGpuLayers = 1), plan, task6Snapshot()))
        assertEquals(NativeLoadPreflight.Invalid, classifyNativeMetadataPreflight(report().copy(fittedContextTokens = 8192), plan, task6Snapshot()))
        assertEquals(NativeLoadPreflight.Invalid, classifyNativeMetadataPreflight(report(model = -1), plan, task6Snapshot()))
    }
}
