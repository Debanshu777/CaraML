package com.debanshu777.caraml.ui.viewmodel

import com.debanshu777.caraml.ui.model.ChatMessage

sealed interface ChatUiState {
    data object NoModels : ChatUiState

    data object ModelLoading : ChatUiState

    data class ModelError(val message: String) : ChatUiState

    data class Ready(
        val messages: List<ChatMessage> = emptyList(),
        val isGenerating: Boolean = false,
    ) : ChatUiState
}
