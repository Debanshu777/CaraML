package com.debanshu777.caraml.features.chat.domain

import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.core.storage.localmodel.ModelType
import com.debanshu777.huggingfacemanager.model.DIFFUSERS_BUNDLE_DB_FILENAME
import com.debanshu777.huggingfacemanager.model.PipelineTag
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalModelGenerationClassifierTest {

    private fun entity(
        filename: String,
        pipelineTag: String?,
    ) = LocalModelEntity(
        id = 0L,
        modelId = "test/model",
        filename = filename,
        localPath = "/tmp/$filename",
        sizeBytes = null,
        downloadedAt = 0L,
        author = null,
        libraryName = null,
        pipelineTag = pipelineTag,
        usageCount = 0,
        contextLength = null,
    )

    private fun assertModes(
        model: LocalModelEntity,
        text: Boolean,
        image: Boolean,
        video: Boolean,
    ) {
        assertTrue(model.matchesGenerationMode(GenerationMode.Text) == text, "Text=$text for $model")
        assertTrue(model.matchesGenerationMode(GenerationMode.Image) == image, "Image=$image for $model")
        assertTrue(model.matchesGenerationMode(GenerationMode.Video) == video, "Video=$video for $model")
    }

    @Test
    fun gguf_text_generation_chatOnly() {
        val m = entity("w.gguf", PipelineTag.TEXT_GENERATION.apiValue)
        assertModes(m, text = true, image = false, video = false)
    }

    @Test
    fun gguf_text_generation_instruct_chatOnly() {
        val m = entity("w.gguf", "text-generation-instruct")
        assertModes(m, text = true, image = false, video = false)
    }

    @Test
    fun gguf_null_tag_chatOnly() {
        val m = entity("w.gguf", null)
        assertModes(m, text = true, image = false, video = false)
    }

    @Test
    fun safetensors_text_generation_chatOnly() {
        val m = entity("w.safetensors", PipelineTag.TEXT_GENERATION.apiValue)
        assertModes(m, text = true, image = false, video = false)
    }

    @Test
    fun safetensors_text_to_image_imageOnly() {
        val m = entity("w.safetensors", PipelineTag.TEXT_TO_IMAGE.apiValue)
        assertModes(m, text = false, image = true, video = false)
    }

    @Test
    fun bundle_text_to_image_imageOnly() {
        val m = entity(DIFFUSERS_BUNDLE_DB_FILENAME, PipelineTag.TEXT_TO_IMAGE.apiValue)
        assertModes(m, text = false, image = true, video = false)
    }

    @Test
    fun bundle_text_to_video_videoOnly() {
        val m = entity(DIFFUSERS_BUNDLE_DB_FILENAME, PipelineTag.TEXT_TO_VIDEO.apiValue)
        assertModes(m, text = false, image = false, video = true)
    }

    @Test
    fun gguf_text_to_image_imageOnly() {
        val m = entity("flux.gguf", PipelineTag.TEXT_TO_IMAGE.apiValue)
        assertModes(m, text = false, image = true, video = false)
    }

    @Test
    fun gguf_text_to_video_videoOnly() {
        val m = entity("wan.gguf", PipelineTag.TEXT_TO_VIDEO.apiValue)
        assertModes(m, text = false, image = false, video = true)
    }

    @Test
    fun safetensors_null_tag_imageOnly_notChat() {
        val m = entity("epoch=0.safetensors", null)
        assertModes(m, text = false, image = true, video = false)
    }

    @Test
    fun safetensors_text_to_video_videoNotImage() {
        val m = entity("t2v.safetensors", PipelineTag.TEXT_TO_VIDEO.apiValue)
        assertModes(m, text = false, image = false, video = true)
    }

    @Test
    fun filterForMode_respectsVideo() {
        val image = entity("a.safetensors", PipelineTag.TEXT_TO_IMAGE.apiValue)
        val vid = entity("b.safetensors", PipelineTag.TEXT_TO_VIDEO.apiValue)
        val list = listOf(image, vid)
        assertTrue(list.filterForMode(GenerationMode.Image).single() == image)
        assertTrue(list.filterForMode(GenerationMode.Video).single() == vid)
    }

    @Test
    fun gguf_with_image_model_type_from_hub_imageOnly_even_if_pipeline_tag_null() {
        val m = LocalModelEntity(
            id = 0L,
            modelId = "test/anima",
            filename = "anima-preview3-base-Q6_K.gguf",
            localPath = "/tmp/anima.gguf",
            sizeBytes = null,
            downloadedAt = 0L,
            author = null,
            libraryName = null,
            pipelineTag = null,
            usageCount = 0,
            contextLength = null,
            modelType = ModelType.IMAGE,
        )
        assertModes(m, text = false, image = true, video = false)
    }
}
