package com.debanshu777.caraml.features.chat.domain

import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.core.storage.localmodel.ModelType
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

private fun LocalModelEntity.hasExplicitModelType(): Boolean =
    when (modelType) {
        ModelType.TEXT, ModelType.IMAGE, ModelType.VIDEO -> true
        else -> false
    }

/**
 * Text / chat: HF text-generation tag (and variants), or GGUF (defaults to LLM when tag is unknown).
 * When [LocalModelEntity.modelType] is set at download time, that value wins.
 */
fun LocalModelEntity.isTextChatModel(): Boolean {
    if (hasExplicitModelType()) {
        return modelType == ModelType.TEXT
    }
    if (filename == DIFFUSERS_BUNDLE_DB_FILENAME) return false
    if (PipelineTag.isTextGenerationTag(pipelineTag)) return true
    // GGUF is used for both LLMs and diffusion; prefer HF pipeline tag when present.
    if (PipelineTag.isDiffusionPipelineTag(pipelineTag)) return false
    if (isGguf()) return true
    if (isDiffusionFileExtension()) return false
    return !PipelineTag.isDiffusionPipelineTag(pipelineTag)
}

/**
 * Image generation: diffusion checkpoints / bundles that are not video-only and not LLM weights.
 */
fun LocalModelEntity.isDiffusionMediaModel(): Boolean {
    if (hasExplicitModelType()) {
        return modelType == ModelType.IMAGE
    }
    if (filename == DIFFUSERS_BUNDLE_DB_FILENAME) {
        if (PipelineTag.isTextGenerationTag(pipelineTag)) return false
        if (PipelineTag.isVideoPipelineTag(pipelineTag)) return false
        return true
    }
    if (PipelineTag.isTextGenerationTag(pipelineTag)) return false
    if (PipelineTag.isVideoPipelineTag(pipelineTag)) return false
    if (PipelineTag.isDiffusionPipelineTag(pipelineTag)) return true
    if (isDiffusionFileExtension()) return true
    return false
}

/** Video generation: models tagged for T2V / I2V (including diffusers bundle with video tag). */
fun LocalModelEntity.isVideoCapableModel(): Boolean {
    if (hasExplicitModelType()) {
        return modelType == ModelType.VIDEO
    }
    if (PipelineTag.isTextGenerationTag(pipelineTag)) return false
    return PipelineTag.isVideoPipelineTag(pipelineTag)
}

fun LocalModelEntity.matchesGenerationMode(mode: GenerationMode): Boolean =
    when (mode) {
        GenerationMode.Text -> isTextChatModel()
        GenerationMode.Image -> isDiffusionMediaModel()
        GenerationMode.Video -> isVideoCapableModel()
    }

fun List<LocalModelEntity>.filterForMode(mode: GenerationMode): List<LocalModelEntity> =
    filter { it.matchesGenerationMode(mode) }
