package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.BackendStatus
import com.debanshu777.caraml.core.platform.DeviceSnapshot
import com.debanshu777.caraml.core.platform.MemoryTopology
import com.debanshu777.caraml.sdk.plugin.llama.bridge.LlamaFitReport
import com.debanshu777.caraml.sdk.plugin.llama.bridge.LlamaMemoryPoolKind

/** Checks native allocation estimates when app metadata cannot supply an analytical footprint. */
internal fun classifyNativeMetadataPreflight(
    report: LlamaFitReport,
    plan: LlmRunPlan,
    snapshot: DeviceSnapshot,
): NativeLoadPreflight {
    if (plan.backend == BackendKind.VULKAN) return classifyUnifiedVulkanPreflight(report, plan, snapshot)
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

private fun classifyUnifiedVulkanPreflight(
    report: LlamaFitReport,
    plan: LlmRunPlan,
    snapshot: DeviceSnapshot,
): NativeLoadPreflight {
    if (plan.memoryTopology != MemoryTopology.UNIFIED ||
        snapshot.hardwareProfile.memoryTopology != MemoryTopology.UNIFIED ||
        plan.gpuLayerCount != null || report.fittedGpuLayers <= 0 ||
        report.fittedContextTokens != plan.contextTokens || report.memoryPools.size != 2
    ) return NativeLoadPreflight.Invalid
    val host = report.memoryPools.singleOrNull { it.kind == LlamaMemoryPoolKind.HOST }
        ?: return NativeLoadPreflight.Invalid
    val gpu = report.memoryPools.singleOrNull {
        it.kind == LlamaMemoryPoolKind.INTEGRATED_GPU || it.kind == LlamaMemoryPoolKind.DISCRETE_GPU
    } ?: return NativeLoadPreflight.Invalid
    if (host.ordinal < 0 || gpu.ordinal < 0 || host.ordinal == gpu.ordinal || gpu.modelBytes <= 0 ||
        report.memoryPools.any { it.modelBytes < 0 || it.contextBytes < 0 || it.computeBytes < 0 ||
            it.freeBytes < 0 || it.totalBytes < it.freeBytes }
    ) return NativeLoadPreflight.Invalid
    val budget = snapshot.baseSharedBudgetBytes
    if (!snapshot.isFresh || budget == null || budget <= 0 ||
        snapshot.hardwareProfile.backends.filter { it.kind != BackendKind.CPU &&
            it.status == BackendStatus.AVAILABLE }.singleOrNull()?.let {
            it.kind == BackendKind.VULKAN && it.availabilityConfidence == Confidence.HIGH
        } != true
    ) return NativeLoadPreflight.Unavailable
    // Host and GPU allocations consume the same physical memory. Charge every
    // reported allocation once against the shared budget, without overflowing.
    var remaining = minOf(budget, host.freeBytes, gpu.freeBytes)
    for (pool in report.memoryPools) {
        for (bytes in listOf(pool.modelBytes, pool.contextBytes, pool.computeBytes)) {
            if (bytes > remaining) return NativeLoadPreflight.NoFit
            remaining -= bytes
        }
    }
    return NativeLoadPreflight.Fit
}
