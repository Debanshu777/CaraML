package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.DeviceSnapshot
import com.debanshu777.caraml.core.platform.MemoryTopology
import com.debanshu777.runner.LlamaFitReport
import com.debanshu777.runner.LlamaMemoryPoolKind

/** Checks native allocation estimates when app metadata cannot supply an analytical footprint. */
internal fun classifyNativeMetadataPreflight(
    report: LlamaFitReport,
    plan: LlmRunPlan,
    snapshot: DeviceSnapshot,
): NativeLoadPreflight {
    if (plan.backend != BackendKind.CPU || report.fittedGpuLayers != 0 ||
        report.fittedContextTokens != plan.contextTokens || report.memoryPools.size != 1
    ) return NativeLoadPreflight.Invalid
    val host = report.memoryPools.single()
    if (host.kind != LlamaMemoryPoolKind.HOST || host.modelBytes <= 0 ||
        host.contextBytes < 0 || host.computeBytes < 0 || host.freeBytes < 0 ||
        host.totalBytes < host.freeBytes
    ) return NativeLoadPreflight.Invalid
    val budget = if (snapshot.hardwareProfile.memoryTopology == MemoryTopology.UNIFIED) {
        snapshot.baseSharedBudgetBytes ?: snapshot.baseHostBudgetBytes
    } else {
        snapshot.baseHostBudgetBytes
    }
    if (!snapshot.isFresh || budget == null || budget <= 0) return NativeLoadPreflight.Unavailable
    // Subtract each allocation separately so a malformed size cannot overflow the sum.
    var remaining = minOf(budget, host.freeBytes)
    for (bytes in listOf(host.modelBytes, host.contextBytes, host.computeBytes)) {
        if (bytes > remaining) return NativeLoadPreflight.NoFit
        remaining -= bytes
    }
    return NativeLoadPreflight.Fit
}
