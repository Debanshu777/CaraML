package com.debanshu777.caraml.features.chat.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.debanshu777.caraml.core.data.inference.DiffusionInferenceRepository
import com.debanshu777.caraml.core.data.inference.InferenceRepository
import com.debanshu777.caraml.core.data.inference.ModelLoadResult
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.features.chat.data.ChatMessage
import com.debanshu777.caraml.features.chat.data.LiveGenerationStats
import com.debanshu777.caraml.features.chat.data.MessageRole
import com.debanshu777.caraml.features.chat.domain.GenerationMode
import com.debanshu777.caraml.features.chat.domain.filterForMode
import com.debanshu777.caraml.features.chat.domain.matchesGenerationMode
import com.debanshu777.caraml.features.chat.domain.usecase.GenerateResponseUseCase
import com.debanshu777.caraml.features.chat.domain.usecase.GenerationResult
import com.debanshu777.caraml.features.chat.domain.usecase.GetAvailableModelsUseCase
import com.debanshu777.caraml.features.chat.domain.usecase.ManageContextUseCase
import com.debanshu777.caraml.features.chat.domain.usecase.TrackModelUsageUseCase
import com.debanshu777.diffusionrunner.ImageGenParams
import com.debanshu777.diffusionrunner.VideoGenParams
import com.debanshu777.runner.StopReason
import com.debanshu777.huggingfacemanager.sdcpp.SdCppRecommendedParams
import com.debanshu777.huggingfacemanager.sdcpp.getModelSetup
import com.debanshu777.huggingfacemanager.sdcpp.SdCppComponentChecker
import com.debanshu777.huggingfacemanager.download.StoragePathProvider
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.time.Clock

private sealed class InternalChatState {
    data object NoModels : InternalChatState()

    data class NoModelsForMode(
        val mode: GenerationMode,
    ) : InternalChatState()

    data object ModelLoading : InternalChatState()

    data class ModelError(
        val message: String,
    ) : InternalChatState()

    data class MissingComponents(
        val missingComponentLabels: List<String>,
        val modelName: String,
        val modelId: String,
    ) : InternalChatState()

    data class ReadyCore(
        val contextLimit: Int,
        val isGenerating: Boolean,
    ) : InternalChatState()
}

class ChatViewModel(
    getAvailableModels: GetAvailableModelsUseCase,
    private val generateResponse: GenerateResponseUseCase,
    private val manageContext: ManageContextUseCase,
    private val trackModelUsage: TrackModelUsageUseCase,
    private val inferenceRepository: InferenceRepository,
    private val diffusionRepository: DiffusionInferenceRepository,
    private val storagePathProvider: StoragePathProvider,
) : ViewModel() {

    private val componentChecker = SdCppComponentChecker(storagePathProvider)

    private val _topModels: StateFlow<ImmutableList<LocalModelEntity>> =
        getAvailableModels()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = persistentListOf(),
            )

    private val _generationMode = MutableStateFlow(GenerationMode.Text)
    val generationMode: StateFlow<GenerationMode> = _generationMode.asStateFlow()

    private val _selectedModel = MutableStateFlow<LocalModelEntity?>(null)

    private var lastTextModelId: Long? = null
    private var lastDiffusionModelId: Long? = null

    private val _messages = MutableStateFlow<ImmutableList<ChatMessage>>(persistentListOf())

    private val _internal = MutableStateFlow<InternalChatState>(InternalChatState.NoModels)

    val uiState: StateFlow<ChatUiState> = combine(
        _internal,
        _messages,
        _selectedModel,
        _topModels,
        _generationMode,
    ) { internal, messages, selected, models, mode ->
        val topImm = models.toImmutableList()
        when (internal) {
            is InternalChatState.NoModels -> ChatUiState.NoModels
            is InternalChatState.NoModelsForMode ->
                ChatUiState.NoModelsForMode(mode = mode)
            is InternalChatState.ModelLoading -> ChatUiState.ModelLoading
            is InternalChatState.ModelError -> ChatUiState.ModelError(internal.message)
            is InternalChatState.MissingComponents -> ChatUiState.MissingComponents(
                missingComponentLabels = internal.missingComponentLabels,
                modelName = internal.modelName,
                modelId = internal.modelId,
            )
            is InternalChatState.ReadyCore -> ChatUiState.Ready(
                messages = messages,
                contextLimit = internal.contextLimit,
                selectedModel = selected,
                topModels = topImm,
                generationMode = mode,
                isGenerating = internal.isGenerating,
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ChatUiState.NoModels,
    )

    private val _streamingState = MutableStateFlow(StreamingState())
    val streamingState: StateFlow<StreamingState> = _streamingState.asStateFlow()

    /** Recommended params from the registry for the currently-loaded diffusion model. */
    private val _currentDiffusionParams = MutableStateFlow<SdCppRecommendedParams?>(null)
    val currentDiffusionParams: StateFlow<SdCppRecommendedParams?> = _currentDiffusionParams.asStateFlow()

    private var modelLoadJob: Job? = null
    private var generationJob: Job? = null

    private val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private suspend fun releaseAllRunners() {
        inferenceRepository.unloadModel()
        diffusionRepository.release()
    }

    init {
        combine(_topModels, _generationMode) { models, mode -> models to mode }
            .onEach { (models, mode) -> ensureSelectionForInventory(models, mode) }
            .launchIn(viewModelScope)

        _selectedModel
            .filterNotNull()
            .distinctUntilChanged { old, new -> old.id == new.id }
            .onEach { model -> loadModel(model) }
            .launchIn(viewModelScope)

        // Forward native denoising-step progress into StreamingState so the UI can show "3/20"
        // (or "Preparing…" while pre-sampling work runs).
        diffusionRepository.imageGenProgress
            .onEach { progress ->
                if (progress != null) {
                    _streamingState.update {
                        it.copy(
                            imageGenStep = progress.step,
                            imageGenTotalSteps = progress.totalSteps,
                            imageGenRequestedSteps = progress.requestedSteps,
                            imageGenElapsedSeconds = progress.elapsedSeconds,
                        )
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    private suspend fun ensureSelectionForInventory(
        models: ImmutableList<LocalModelEntity>,
        mode: GenerationMode,
    ) {
        if (models.isEmpty()) {
            releaseAllRunners()
            _internal.value = InternalChatState.NoModels
            _selectedModel.value = null
            return
        }
        val picker = models.filterForMode(mode)
        if (picker.isEmpty()) {
            releaseAllRunners()
            _internal.value = InternalChatState.NoModelsForMode(mode)
            _selectedModel.value = null
            return
        }
        val sel = _selectedModel.value
        if (sel == null || !sel.matchesGenerationMode(mode)) {
            val next = pickRememberedModel(picker, mode) ?: picker.first()
            if (sel?.id != next.id) {
                _internal.value = InternalChatState.ModelLoading
                _selectedModel.value = next
            }
        } else if (
            _internal.value is InternalChatState.NoModels ||
            _internal.value is InternalChatState.NoModelsForMode
        ) {
            loadModel(sel)
        }
    }

    private fun pickRememberedModel(
        picker: List<LocalModelEntity>,
        mode: GenerationMode,
    ): LocalModelEntity? {
        val id = when (mode) {
            GenerationMode.Text -> lastTextModelId
            GenerationMode.Image,
            GenerationMode.Video,
            -> lastDiffusionModelId
        } ?: return null
        return picker.find { it.id == id }
    }

    override fun onCleared() {
        super.onCleared()
        teardownScope.launch {
            inferenceRepository.unloadModel()
            diffusionRepository.release()
        }
    }

    fun setGenerationMode(mode: GenerationMode) {
        if (_generationMode.value == mode) return
        modelLoadJob?.cancel()
        generationJob?.cancel()
        if (_generationMode.value == GenerationMode.Text) {
            inferenceRepository.cancelGeneration()
        }
        _streamingState.value = StreamingState()
        _generationMode.value = mode

        val models = _topModels.value
        val picker = models.filterForMode(mode)
        if (models.isEmpty()) {
            _internal.value = InternalChatState.NoModels
            _selectedModel.value = null
            viewModelScope.launch(Dispatchers.Default) { releaseAllRunners() }
            return
        }
        if (picker.isEmpty()) {
            _internal.value = InternalChatState.NoModelsForMode(mode)
            _selectedModel.value = null
            viewModelScope.launch(Dispatchers.Default) { releaseAllRunners() }
            return
        }
        val previous = _selectedModel.value
        val next = pickRememberedModel(picker, mode) ?: picker.first()
        _internal.value = InternalChatState.ModelLoading
        _selectedModel.value = next
        if (previous?.id == next.id) {
            loadModel(next)
        }
    }

    fun selectModel(model: LocalModelEntity) {
        _selectedModel.value = model
        when (_generationMode.value) {
            GenerationMode.Text -> lastTextModelId = model.id
            GenerationMode.Image,
            GenerationMode.Video,
            -> lastDiffusionModelId = model.id
        }
        viewModelScope.launch { trackModelUsage(model) }
    }

    private fun loadModel(model: LocalModelEntity) {
        val previousJob = modelLoadJob
        modelLoadJob?.cancel()
        generationJob?.cancel()
        _streamingState.value = StreamingState()

        val mode = _generationMode.value
        if (!model.matchesGenerationMode(mode)) {
            return
        }

        _internal.value = InternalChatState.ModelLoading

        modelLoadJob = viewModelScope.launch(Dispatchers.Default) {
            // Wait for the previous job to fully complete (including any in-progress JNI call)
            // before we start new native operations. Without this, a cancelled job that is still
            // inside a blocking JNI call races with our unloadModel() → double-free crash.
            previousJob?.join()

            val result: ModelLoadResult = when (mode) {
                GenerationMode.Text -> {
                    diffusionRepository.release()
                    inferenceRepository.loadModel(model)
                }
                GenerationMode.Image,
                GenerationMode.Video,
                -> {
                    inferenceRepository.unloadModel()
                    
                    // Pre-inference validation for diffusion models
                    val modelSetup = getModelSetup(model.modelId)
                    if (modelSetup != null && !modelSetup.selfContained) {
                        val missingComponents = componentChecker.getMissingComponents(modelSetup)
                        if (missingComponents.isNotEmpty()) {
                            val missingLabels = missingComponents.map { it.role.displayLabel }
                            val modelName = modelSetup.familyLabel
                            _internal.value = InternalChatState.MissingComponents(
                                missingComponentLabels = missingLabels,
                                modelName = modelName,
                                modelId = model.modelId,
                            )
                            return@launch
                        }
                    }
                    
                    diffusionRepository.loadModel(model)
                }
            }
            when (result) {
                is ModelLoadResult.Success -> {
                    if (mode == GenerationMode.Image || mode == GenerationMode.Video) {
                        _currentDiffusionParams.value = diffusionRepository.getRecommendedParams(model)
                    }
                    _internal.value = InternalChatState.ReadyCore(
                        contextLimit = result.contextSize,
                        isGenerating = false,
                    )
                }
                is ModelLoadResult.Error -> {
                    _internal.value = InternalChatState.ModelError(result.message)
                }
            }
        }
    }

    fun sendMessage(text: String) {
        val content = text.trim()
        if (content.isEmpty()) return
        val core = _internal.value
        if (core !is InternalChatState.ReadyCore || core.isGenerating) return

        when (_generationMode.value) {
            GenerationMode.Text -> sendTextMessage(content)
            GenerationMode.Image -> sendImageMessage(content)
            GenerationMode.Video -> sendVideoMessage(content)
        }
    }

    private fun sendTextMessage(content: String) {
        val userMessage = ChatMessage(role = MessageRole.User, text = content)
        val assistantMessage = ChatMessage(role = MessageRole.Assistant, text = "")

        generationJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                val currentMessages = _messages.value
                if (manageContext.needsReset()) {
                    handleContextReset(currentMessages)
                }
                appendMessages(userMessage, assistantMessage)
                val result = generateResponse(userMessage.text) { thinking, output, stats ->
                    updateStreamingState(thinking, output, stats)
                }
                finalizeMessage(assistantMessage.id, result)
                if (result.stopReason == StopReason.CONTEXT_FULL) {
                    val m = _messages.value
                    handleContextReset(m)
                }
            } catch (_: CancellationException) {
            } catch (_: Exception) {
                finalizeWithError(assistantMessage.id)
            }
        }
    }

    private fun sendImageMessage(content: String) {
        val (prompt, negative) = splitPromptAndNegative(content)
        val userMessage = ChatMessage(role = MessageRole.User, text = content)
        val assistantMessage = ChatMessage(role = MessageRole.Assistant, text = "")
        appendMessagesForMedia(userMessage, assistantMessage, pendingMedia = true)

        generationJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                val rp = _currentDiffusionParams.value
                val params = ImageGenParams(
                    prompt = prompt,
                    negativePrompt = negative,
                    width = rp?.width ?: 512,
                    height = rp?.height ?: 512,
                    steps = rp?.steps ?: 20,
                    cfgScale = rp?.cfgScale ?: 7f,
                    seed = Clock.System.now().toEpochMilliseconds(),
                )
                val result = diffusionRepository.generateImage(params)
                result.fold(
                    onSuccess = { bytes ->
                        if (bytes.isEmpty()) {
                            finalizeWithError(assistantMessage.id)
                        } else {
                            finalizeMediaMessage(assistantMessage.id, imageBytes = bytes)
                        }
                    },
                    onFailure = {
                        finalizeWithError(assistantMessage.id)
                    },
                )
            } catch (_: CancellationException) {
            } catch (_: Exception) {
                finalizeWithError(assistantMessage.id)
            }
        }
    }

    private fun sendVideoMessage(content: String) {
        val (prompt, negative) = splitPromptAndNegative(content)
        val userMessage = ChatMessage(role = MessageRole.User, text = content)
        val assistantMessage = ChatMessage(role = MessageRole.Assistant, text = "")
        appendMessagesForMedia(userMessage, assistantMessage, pendingMedia = true)

        generationJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                val rp = _currentDiffusionParams.value
                val params = VideoGenParams(
                    prompt = prompt,
                    negativePrompt = negative,
                    width = rp?.width ?: 512,
                    height = rp?.height ?: 512,
                    videoFrames = 16,
                    steps = rp?.steps ?: 20,
                    cfgScale = rp?.cfgScale ?: 7f,
                    seed = Clock.System.now().toEpochMilliseconds(),
                )
                val result = diffusionRepository.generateVideo(params)
                result.fold(
                    onSuccess = { frames ->
                        if (frames.isEmpty()) {
                            finalizeWithError(assistantMessage.id)
                        } else {
                            finalizeMediaMessage(assistantMessage.id, videoFrames = frames)
                        }
                    },
                    onFailure = {
                        finalizeWithError(assistantMessage.id)
                    },
                )
            } catch (_: CancellationException) {
            } catch (_: Exception) {
                finalizeWithError(assistantMessage.id)
            }
        }
    }

    private fun splitPromptAndNegative(query: String): Pair<String, String> {
        if (!query.contains('|')) return query to ""
        val parts = query.split('|', limit = 2)
        return parts[0].trim() to parts.getOrElse(1) { "" }.trim()
    }

    private fun appendMessages(userMessage: ChatMessage, assistantMessage: ChatMessage) {
        _streamingState.value = StreamingState(streamingMessageId = assistantMessage.id)
        _messages.update { (it + userMessage + assistantMessage).toImmutableList() }
        updateReadyCore { it.copy(isGenerating = true) }
    }

    private fun appendMessagesForMedia(
        userMessage: ChatMessage,
        assistantMessage: ChatMessage,
        pendingMedia: Boolean,
    ) {
        _streamingState.value = StreamingState(
            streamingMessageId = assistantMessage.id,
            pendingMediaGeneration = pendingMedia,
        )
        _messages.update { (it + userMessage + assistantMessage).toImmutableList() }
        updateReadyCore { it.copy(isGenerating = true) }
    }

    private fun updateReadyCore(
        block: (InternalChatState.ReadyCore) -> InternalChatState.ReadyCore,
    ) {
        val v = _internal.value
        if (v is InternalChatState.ReadyCore) {
            _internal.value = block(v)
        }
    }

    private fun updateStreamingState(
        thinkingText: String,
        outputText: String,
        liveStats: LiveGenerationStats,
    ) {
        _streamingState.value = StreamingState(
            streamingText = outputText,
            streamingThinkingText = thinkingText,
            streamingMessageId = _streamingState.value.streamingMessageId,
            liveStats = liveStats,
        )
    }

    private fun finalizeMessage(assistantMessageId: String, result: GenerationResult) {
        val state = _streamingState.value
        val finalText = state.streamingText
        val finalThinking = state.streamingThinkingText.takeIf { it.isNotBlank() }
        _messages.update { list ->
            val messages = list.toMutableList()
            val idx = messages.indexOfLast { it.id == assistantMessageId }
            if (idx >= 0) {
                messages[idx] = messages[idx].copy(
                    text = finalText,
                    thinking = finalThinking,
                    inferenceMetrics = result.metrics,
                )
            }
            messages.toImmutableList()
        }
        updateReadyCore { it.copy(isGenerating = false) }
        _streamingState.value = StreamingState()
    }

    private fun finalizeMediaMessage(
        assistantMessageId: String,
        imageBytes: ByteArray? = null,
        videoFrames: List<ByteArray>? = null,
    ) {
        _messages.update { list ->
            val messages = list.toMutableList()
            val idx = messages.indexOfLast { it.id == assistantMessageId }
            if (idx >= 0) {
                val meta = buildMap {
                    put("prompt", messages.getOrNull(idx - 1)?.text ?: "")
                }
                messages[idx] = messages[idx].copy(
                    text = "",
                    imageBytes = imageBytes,
                    videoFrames = videoFrames,
                    metadata = meta,
                )
            }
            messages.toImmutableList()
        }
        updateReadyCore { it.copy(isGenerating = false) }
        _streamingState.value = StreamingState()
    }

    private fun finalizeWithError(assistantMessageId: String) {
        _messages.update { list ->
            val messages = list.toMutableList()
            val idx = messages.indexOfLast { it.id == assistantMessageId }
            if (idx >= 0) {
                messages[idx] = messages[idx].copy(
                    text = "Something went wrong. Please try again.",
                )
            }
            messages.toImmutableList()
        }
        updateReadyCore { it.copy(isGenerating = false) }
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
            text = text,
        )
        _messages.update { (it + progressMessage).toImmutableList() }
        return progressMessageId
    }

    private fun updateProgressMessage(messageId: String, newText: String) {
        _messages.update { list ->
            val messages = list.toMutableList()
            val idx = messages.indexOfFirst { it.id == messageId }
            if (idx >= 0) {
                messages[idx] = messages[idx].copy(text = newText)
            }
            messages.toImmutableList()
        }
    }

    fun cancelGeneration() {
        updateReadyCore { it.copy(isGenerating = false) }
        _streamingState.value = StreamingState()
        if (_generationMode.value == GenerationMode.Text) {
            inferenceRepository.cancelGeneration()
        }
        generationJob?.cancel()
    }
}
