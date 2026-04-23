package com.debanshu777.caraml.features.chat.presentation

import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.features.chat.data.ChatMessage
import com.debanshu777.caraml.features.chat.data.LiveGenerationStats
import com.debanshu777.caraml.features.chat.domain.GenerationMode
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

sealed interface ChatUiState {
    data object NoModels : ChatUiState

    data class NoModelsForMode(
        val mode: GenerationMode,
    ) : ChatUiState

    data object ModelLoading : ChatUiState

    data class ModelError(val message: String) : ChatUiState

    data class MissingComponents(
        val missingComponentLabels: List<String>,
        val modelName: String,
    ) : ChatUiState

    data class Ready(
        val messages: ImmutableList<ChatMessage> = persistentListOf(),
        val contextLimit: Int = 0,
        val selectedModel: LocalModelEntity? = null,
        val topModels: ImmutableList<LocalModelEntity> = persistentListOf(),
        val generationMode: GenerationMode = GenerationMode.Text,
        val isGenerating: Boolean = false,
    ) : ChatUiState
}

data class StreamingState(
    val streamingText: String = "",
    val streamingThinkingText: String = "",
    val streamingMessageId: String? = null,
    val liveStats: LiveGenerationStats? = null,
    val pendingMediaGeneration: Boolean = false,
)
