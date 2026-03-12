package com.debanshu777.caraml.features.chat.presentation

import com.debanshu777.caraml.features.chat.data.ChatMessage
import com.debanshu777.caraml.features.chat.data.LiveGenerationStats

sealed interface ChatUiState {
    data object NoModels : ChatUiState

    data object ModelLoading : ChatUiState

    data class ModelError(val message: String) : ChatUiState

    data class Ready(
        val messages: List<ChatMessage> = emptyList(),
        val streamingText: String = "",
        val streamingMessageId: String? = null,
        val isGenerating: Boolean = false,
        val liveStats: LiveGenerationStats? = null,
    ) : ChatUiState
}
