package com.debanshu777.caraml.features.chat.presentation.components.preview.providers

import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import kotlin.time.Clock

class LocalModelPreviewProvider : PreviewParameterProvider<LocalModelEntity> {
    override val values: Sequence<LocalModelEntity> = sequenceOf(
        LocalModelEntity(
            id = 1,
            modelId = "meta-llama/Llama-3.2-1B",
            filename = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            localPath = "/data/models/llama.gguf",
            sizeBytes = 734003200L,
            downloadedAt = Clock.System.now().epochSeconds,
            author = "meta-llama",
            libraryName = "transformers",
            pipelineTag = "text-generation",
            usageCount = 5,
            contextLength = 8192
        ),
        LocalModelEntity(
            id = 2,
            modelId = "x",
            filename = "tiny.gguf",
            localPath = "/data/tiny.gguf",
            sizeBytes = null,
            downloadedAt = 0L,
            author = null,
            libraryName = null,
            pipelineTag = null,
            usageCount = 0,
            contextLength = null
        ),
        LocalModelEntity(
            id = 3,
            modelId = "organization/very-long-model-name-that-exceeds-typical-display-width-for-truncation-testing",
            filename = "extremely-long-filename-that-might-overflow-the-ui.gguf",
            localPath = "/data/long/path/model.gguf",
            sizeBytes = 2147483648L,
            downloadedAt = Clock.System.now().epochSeconds,
            author = "very-long-organization-name",
            libraryName = "transformers",
            pipelineTag = "text-generation-instruct",
            usageCount = 999,
            contextLength = 32768
        ),
    )
}
