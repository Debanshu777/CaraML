package com.debanshu777.caraml.features.chat.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.debanshu777.caraml.core.domain.InferenceRepository
import com.debanshu777.caraml.core.domain.ModelLoadResult
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.core.storage.localmodel.LocalModelRepository
import com.debanshu777.caraml.features.chat.data.ChatMessage
import com.debanshu777.caraml.features.chat.data.MessageRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ChatViewModel(
    private val localModelRepository: LocalModelRepository,
    private val inferenceRepository: InferenceRepository,
) : ViewModel() {

    private val allDownloadedModels: StateFlow<List<LocalModelEntity>> =
        localModelRepository.getAllDownloadedFiles()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

    val topModels: StateFlow<List<LocalModelEntity>> =
        allDownloadedModels
            .map { models -> models.take(3) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

    private val _selectedModel = MutableStateFlow<LocalModelEntity?>(null)
    val selectedModel: StateFlow<LocalModelEntity?> = _selectedModel.asStateFlow()

    private val _uiState = MutableStateFlow<ChatUiState>(ChatUiState.NoModels)
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var modelLoadJob: Job? = null
    private var generationJob: Job? = null

    init {
        viewModelScope.launch {
            allDownloadedModels.collect { models ->
                if (_selectedModel.value == null && models.isNotEmpty()) {
                    _selectedModel.value = models.first()
                } else if (models.isEmpty()) {
                    _uiState.value = ChatUiState.NoModels
                }
            }
        }

        viewModelScope.launch {
            _selectedModel.filterNotNull().collect { model ->
                loadModel(model)
            }
        }
    }

    fun selectModel(model: LocalModelEntity) {
        _selectedModel.value = model
        viewModelScope.launch {
            localModelRepository.incrementUsageCount(model.modelId, model.filename)
        }
    }

    private fun loadModel(model: LocalModelEntity) {
        modelLoadJob?.cancel()
        generationJob?.cancel()
        
        _uiState.value = ChatUiState.ModelLoading

        modelLoadJob = viewModelScope.launch {
            when (val result = inferenceRepository.loadModel(model)) {
                is ModelLoadResult.Success -> {
                    _uiState.value = ChatUiState.Ready()
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

        _uiState.update { state ->
            if (state is ChatUiState.Ready) {
                state.copy(
                    messages = state.messages + userMessage + assistantMessage,
                    isGenerating = true
                )
            } else state
        }

        generationJob = viewModelScope.launch {
            var wasCancelled = false
            try {
                inferenceRepository.generateResponse(userMessage.text)
                    .conflate()
                    .flowOn(Dispatchers.IO)
                    .collect { token ->
                        _uiState.update { state ->
                            if (state is ChatUiState.Ready) {
                                val messages = state.messages.toMutableList()
                                val idx = messages.indexOfFirst { it.id == assistantMessage.id }
                                if (idx >= 0) {
                                    messages[idx] = messages[idx].copy(text = messages[idx].text + token)
                                }
                                state.copy(messages = messages, isGenerating = true)
                            } else state
                        }
                    }
            } catch (e: CancellationException) {
                wasCancelled = true
                throw e
            } catch (_: Exception) {
                _uiState.update { state ->
                    if (state is ChatUiState.Ready) {
                        val messages = state.messages.toMutableList()
                        val idx = messages.indexOfFirst { it.id == assistantMessage.id }
                        if (idx >= 0) {
                            messages[idx] = messages[idx].copy(
                                text = "Something went wrong. Please try again."
                            )
                        }
                        state.copy(messages = messages, isGenerating = false)
                    } else state
                }
            } finally {
                if (!wasCancelled) {
                    _uiState.update { state ->
                        if (state is ChatUiState.Ready) {
                            state.copy(isGenerating = false)
                        } else state
                    }
                }
            }
        }
    }

    fun cancelGeneration() {
        inferenceRepository.cancelGeneration()
        generationJob?.cancel()
    }
}
