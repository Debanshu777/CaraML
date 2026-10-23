package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.MemoryTopology

/** These pinned llama.cpp architectures count transformer blocks plus the output layer. */
internal fun LlmModelDescriptor.fullVulkanLayerCount(): Int? {
    if (format != ModelFormat.GGUF || !hasCompleteLocalCompatibilityMetadata() ||
        architecture?.trim()?.lowercase() !in setOf("llama", "qwen2")
    ) return null
    val shape = transformerShape ?: return null
    val blocks = shape.layerCount ?: return null
    if (blocks !in 1 until Int.MAX_VALUE ||
        listOf(shape.kvHeadCount, shape.attentionHeadCount, shape.hiddenSize, shape.headDim).any { it == null || it <= 0 }
    ) return null
    return blocks + 1
}

/** Called on the existing verified preparation descriptor before assessment normalization. */
internal fun LlmModelDescriptor.validatedFullVulkanLayerCount(): Int? {
    if (evidence.size > DescriptorLimits.MAX_METADATA_COLLECTION_SIZE ||
        evidence.none { it.reason == AssessmentReason.METADATA_VALIDATED && it.confidence != Confidence.LOW &&
            it.detail in setOf("shape:local-gguf-header", "shape:transformer-config") }
    ) return null
    return fullVulkanLayerCount()
}

internal fun LlmRunPlan.matchesFixedVulkanPlacement(descriptor: LlmModelDescriptor): Boolean =
    when (backend) {
        BackendKind.CPU -> gpuLayerCount == 0
        BackendKind.VULKAN -> memoryTopology == MemoryTopology.UNIFIED &&
            descriptor.fullVulkanLayerCount()?.let { gpuLayerCount == it } == true
        else -> false
    }
