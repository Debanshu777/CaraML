package com.debanshu777.caraml.features.chat.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.debanshu777.caraml.core.domain.InferenceRepository
import com.debanshu777.caraml.core.domain.ModelLoadResult
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.features.chat.data.ChatMessage
import com.debanshu777.caraml.features.chat.data.LiveGenerationStats
import com.debanshu777.caraml.features.chat.data.MessageRole
import com.debanshu777.caraml.features.chat.domain.usecase.GenerateResponseUseCase
import com.debanshu777.caraml.features.chat.domain.usecase.GenerationResult
import com.debanshu777.caraml.features.chat.domain.usecase.GetAvailableModelsUseCase
import com.debanshu777.caraml.features.chat.domain.usecase.ManageContextUseCase
import com.debanshu777.caraml.features.chat.domain.usecase.TrackModelUsageUseCase
import com.debanshu777.runner.StopReason
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock

class ChatViewModel(
    getAvailableModels: GetAvailableModelsUseCase,
    private val generateResponse: GenerateResponseUseCase,
    private val manageContext: ManageContextUseCase,
    private val trackModelUsage: TrackModelUsageUseCase,
    private val inferenceRepository: InferenceRepository,
) : ViewModel() {

    private val _topModels: StateFlow<ImmutableList<LocalModelEntity>> =
        getAvailableModels()
            .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = persistentListOf()
        )

    private val _selectedModel = MutableStateFlow<LocalModelEntity?>(null)

    private val _uiState = MutableStateFlow<ChatUiState>(ChatUiState.NoModels)

    val uiState: StateFlow<ChatUiState> = combine(
        _uiState, _selectedModel, _topModels
    ) { state, selected, models ->
        if (state is ChatUiState.Ready) state.copy(selectedModel = selected, topModels = models)
        else state
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ChatUiState.NoModels
    )

    private val _streamingState = MutableStateFlow(StreamingState())
    val streamingState: StateFlow<StreamingState> = _streamingState.asStateFlow()

    private var modelLoadJob: Job? = null
    private var generationJob: Job? = null

    init {
        _topModels
            .map { models ->
                if (models.isEmpty()) {
                    _uiState.value = ChatUiState.NoModels
                } else if (_selectedModel.value == null) {
                    _selectedModel.value = models.first()
                }
            }
            .launchIn(viewModelScope)

        _selectedModel
            .filterNotNull()
            .distinctUntilChanged { old, new -> old.id == new.id }
            .onEach { model -> loadModel(model) }
            .launchIn(viewModelScope)
    }

    override fun onCleared() {
        super.onCleared()
        inferenceRepository.unloadModel()
    }

    fun selectModel(model: LocalModelEntity) {
        _selectedModel.value = model
        viewModelScope.launch { trackModelUsage(model) }
    }

    private fun loadModel(model: LocalModelEntity) {
        modelLoadJob?.cancel()
        generationJob?.cancel()
        _streamingState.value = StreamingState()

        _uiState.value = ChatUiState.ModelLoading

        modelLoadJob = viewModelScope.launch(Dispatchers.IO) {
            when (val result = inferenceRepository.loadModel(model)) {
                is ModelLoadResult.Success -> {
                    _uiState.value = ChatUiState.Ready(contextLimit = result.contextSize)
                }
                is ModelLoadResult.Error -> {
                    _uiState.value = ChatUiState.ModelError(result.message)
                }
            }
        }
    }

    fun sendMessage(text: String) {
        val currentState = _uiState.value
        if (currentState !is ChatUiState.Ready || currentState.isGenerating) return

        val userMessage = ChatMessage(role = MessageRole.User, text = text.trim())
        val assistantMessage = ChatMessage(role = MessageRole.Assistant, text = "")

        generationJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                if (manageContext.needsReset()) {
                    handleContextReset(currentState.messages)
                }

                appendMessages(userMessage, assistantMessage)

                val result = generateResponse(userMessage.text) { accText, stats ->
                    updateStreamingState(accText, stats)
                }

                finalizeMessage(assistantMessage.id, result)

                if (result.stopReason == StopReason.CONTEXT_FULL) {
                    val state = _uiState.value
                    if (state is ChatUiState.Ready) {
                        handleContextReset(state.messages)
                    }
                }
            } catch (_: CancellationException) {
                // Silently handle cancellation
            } catch (_: Exception) {
                finalizeWithError(assistantMessage.id)
            }
        }
    }

    private fun appendMessages(userMessage: ChatMessage, assistantMessage: ChatMessage) {
        _streamingState.value = StreamingState(streamingMessageId = assistantMessage.id)
        _uiState.update { state ->
            if (state is ChatUiState.Ready) {
                state.copy(
                    messages = (state.messages + userMessage + assistantMessage).toImmutableList(),
                    isGenerating = true
                )
            } else state
        }
    }

    private fun updateStreamingState(accumulatedText: String, liveStats: LiveGenerationStats) {
        _streamingState.value = StreamingState(
            streamingText = accumulatedText,
            streamingMessageId = _streamingState.value.streamingMessageId,
            liveStats = liveStats
        )
    }

    private fun finalizeMessage(assistantMessageId: String, result: GenerationResult) {
        val finalText = _streamingState.value.streamingText
        _uiState.update { state ->
            if (state is ChatUiState.Ready) {
                val messages = state.messages.toMutableList()
                val idx = messages.indexOfLast { it.id == assistantMessageId }
                if (idx >= 0) {
                    messages[idx] = messages[idx].copy(
                        text = finalText,
                        inferenceMetrics = result.metrics
                    )
                }
                state.copy(
                    messages = messages.toImmutableList(),
                    isGenerating = false
                )
            } else state
        }
        _streamingState.value = StreamingState()
    }

    private fun finalizeWithError(assistantMessageId: String) {
        _uiState.update { state ->
            if (state is ChatUiState.Ready) {
                val messages = state.messages.toMutableList()
                val idx = messages.indexOfLast { it.id == assistantMessageId }
                if (idx >= 0) {
                    messages[idx] = messages[idx].copy(
                        text = "Something went wrong. Please try again."
                    )
                }
                state.copy(
                    messages = messages.toImmutableList(),
                    isGenerating = false
                )
            } else state
        }
        _streamingState.value = StreamingState()
    }

    private suspend fun handleContextReset(messages: List<ChatMessage>) {
        val progressMessageId = addProgressMessage("Chat summarization in progress")
        manageContext.resetContext(messages)
        updateProgressMessage(progressMessageId, "Chat summarized")
    }

    private fun addProgressMessage(text: String): String {
        val progressMessageId = "context_reset_${Clock.System.now()}"
        val progressMessage = ChatMessage(
            id = progressMessageId,
            role = MessageRole.System,
            text = text
        )

        _uiState.update { state ->
            if (state is ChatUiState.Ready) {
                state.copy(messages = (state.messages + progressMessage).toImmutableList())
            } else state
        }

        return progressMessageId
    }

    private fun updateProgressMessage(messageId: String, newText: String) {
        _uiState.update { state ->
            if (state is ChatUiState.Ready) {
                val messages = state.messages.toMutableList()
                val idx = messages.indexOfFirst { it.id == messageId }
                if (idx >= 0) {
                    messages[idx] = messages[idx].copy(text = newText)
                }
                state.copy(messages = messages.toImmutableList())
            } else state
        }
    }

    fun cancelGeneration() {
        _uiState.update { state ->
            if (state is ChatUiState.Ready) {
                state.copy(isGenerating = false)
            } else state
        }
        _streamingState.value = StreamingState()
        inferenceRepository.cancelGeneration()
        generationJob?.cancel()
    }
}
