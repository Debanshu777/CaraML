package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.MemoryTopology
import com.debanshu777.runner.LlamaFitReport
import com.debanshu777.runner.LlamaPreflightMemoryPool
import com.debanshu777.runner.LlamaMemoryPoolKind
import kotlin.test.Test
import kotlin.test.assertEquals

class NativeMetadataLoadPreflightTest {
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
