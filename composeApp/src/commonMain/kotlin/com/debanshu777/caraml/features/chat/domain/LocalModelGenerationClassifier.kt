package com.debanshu777.caraml.features.chat.domain

import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.huggingfacemanager.model.DIFFUSERS_BUNDLE_DB_FILENAME
import com.debanshu777.huggingfacemanager.model.PipelineTag

private fun LocalModelEntity.filenameLower(): String = filename.lowercase()

private fun LocalModelEntity.isGguf(): Boolean = filenameLower().endsWith(".gguf")

private fun LocalModelEntity.isDiffusionFileExtension(): Boolean {
    val n = filenameLower()
    return n.endsWith(".safetensors") ||
        n.endsWith(".ckpt") ||
        n.endsWith(".pth")
}

/**
 * Text / chat: HF text-generation tag, or GGUF (defaults to LLM in this app).
 */
fun LocalModelEntity.isTextChatModel(): Boolean {
    if (filename == DIFFUSERS_BUNDLE_DB_FILENAME) return false
    if (PipelineTag.isTextGenerationTag(pipelineTag)) return true
    if (isGguf()) return true
    if (isDiffusionFileExtension()) return false
    return !PipelineTag.isDiffusionPipelineTag(pipelineTag)
}

/**
 * Image or video generation: diffusion-style weights (by tag or typical checkpoint extensions).
 * Non–text-generation GGUF with a diffusion-related tag is included.
 */
fun LocalModelEntity.isDiffusionMediaModel(): Boolean {
    if (filename == DIFFUSERS_BUNDLE_DB_FILENAME) return true
    if (PipelineTag.isDiffusionPipelineTag(pipelineTag)) return true
    if (isDiffusionFileExtension()) return true
    if (isGguf() && !PipelineTag.isTextGenerationTag(pipelineTag) &&
        pipelineTag != null &&
        pipelineTag.isNotBlank()
    ) {
        return true
    }
    return false
}

fun LocalModelEntity.matchesGenerationMode(mode: GenerationMode): Boolean =
    when (mode) {
        GenerationMode.Text -> isTextChatModel()
        GenerationMode.Image,
        GenerationMode.Video,
        -> isDiffusionMediaModel()
    }

fun List<LocalModelEntity>.filterForMode(mode: GenerationMode): List<LocalModelEntity> =
    filter { it.matchesGenerationMode(mode) }
