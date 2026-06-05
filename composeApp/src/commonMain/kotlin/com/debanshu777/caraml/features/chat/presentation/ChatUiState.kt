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
        /** The HuggingFace model ID — used to deep-link directly to this model's detail page. */
        val modelId: String,
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
    /** Current denoising step (1-based); 0 while pre-sampling work is happening. */
    val imageGenStep: Int = 0,
    /** Total denoising steps reported by the sampler. 0 until the sampling loop starts. */
    val imageGenTotalSteps: Int = 0,
    /** User-configured step budget; known upfront, used for the "Preparing… (will run N steps)" hint. */
    val imageGenRequestedSteps: Int = 0,
    /** Seconds since generateImage() was called — useful while waiting for sampling to begin. */
    val imageGenElapsedSeconds: Int = 0,
)
