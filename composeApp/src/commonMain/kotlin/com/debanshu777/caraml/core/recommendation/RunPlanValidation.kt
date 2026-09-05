package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.MemoryTopology

internal fun validateRunPlan(plan: RunPlan): AssessmentReason? {
    val fieldsAreValid = when (plan) {
        is LlmRunPlan ->
            plan.contextTokens in 1..DescriptorLimits.MAX_CONTEXT_TOKENS &&
                plan.batchSize in 1..WorkloadLimits.MAX_BATCH_SIZE &&
                plan.microBatchSize in 1..plan.batchSize &&
                plan.sequenceCount in 1..WorkloadLimits.MAX_SEQUENCE_COUNT &&
                plan.gpuLayerCount?.let { it >= 0 } != false &&
                (plan.backend != BackendKind.CPU || plan.gpuLayerCount in listOf(null, 0)) &&
                (plan.backend == BackendKind.CPU || plan.gpuLayerCount != 0)

        is DiffusionRunPlan ->
            plan.width in 1..DescriptorLimits.MAX_IMAGE_DIMENSION &&
                plan.height in 1..DescriptorLimits.MAX_IMAGE_DIMENSION &&
                plan.frameCount in 1..WorkloadLimits.MAX_DIFFUSION_FRAMES &&
                plan.batchSize in 1..WorkloadLimits.MAX_BATCH_SIZE &&
                plan.steps in 1..WorkloadLimits.MAX_DIFFUSION_STEPS &&
                (plan.mode != DiffusionMode.IMAGE || plan.frameCount == 1) &&
                plan.maxVramBytes?.let { it in 1..DescriptorLimits.MAX_BUNDLE_BYTES } != false &&
                (plan.backend != BackendKind.CPU || plan.maxVramBytes == null && !plan.layerStreaming) &&
                (plan.backend == BackendKind.CPU || plan.memoryTopology != MemoryTopology.UNKNOWN) &&
                (plan.maxVramBytes == null || plan.memoryTopology == MemoryTopology.DISCRETE) &&
                (!plan.layerStreaming || plan.offloadToCpu)
    }
    if (!fieldsAreValid) return AssessmentReason.INVALID_WORKLOAD
    return if (plan.stableKey == canonicalRunPlanStableKey(plan)) null else AssessmentReason.INVALID_WORKLOAD
}

internal fun canonicalRunPlanStableKey(plan: RunPlan): String = when (plan) {
    is LlmRunPlan -> buildString {
        append("llm:")
        append(plan.contextTokens).append(':')
        append(plan.batchSize).append(':')
        append(plan.microBatchSize).append(':')
        append(plan.sequenceCount).append(':')
        append(plan.keyCacheType.name).append(':')
        append(plan.valueCacheType.name).append(':')
        append(plan.backend.name).append(':')
        append(plan.memoryTopology.name).append(':')
        append(plan.gpuLayerCount?.toString() ?: "auto")
    }

    is DiffusionRunPlan -> buildString {
        append("diffusion:")
        append(plan.mode.name).append(':')
        append(plan.width).append(':')
        append(plan.height).append(':')
        append(plan.frameCount).append(':')
        append(plan.batchSize).append(':')
        append(plan.steps).append(':')
        append(plan.vaeTiling).append(':')
        append(plan.offloadToCpu).append(':')
        append(plan.keepClipOnCpu).append(':')
        append(plan.keepVaeOnCpu).append(':')
        append(plan.maxVramBytes?.toString() ?: "none").append(':')
        append(plan.layerStreaming).append(':')
        append(plan.backend.name).append(':')
        append(plan.memoryTopology.name)
    }
}
