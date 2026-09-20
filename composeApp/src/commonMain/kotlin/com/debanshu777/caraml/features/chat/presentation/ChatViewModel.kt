package com.debanshu777.caraml.features.chat.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.debanshu777.caraml.core.data.inference.DiffusionInferenceRepository
import com.debanshu777.caraml.core.data.inference.DiffusionMemoryException
import com.debanshu777.caraml.core.data.inference.InferenceRepository
import com.debanshu777.caraml.core.data.inference.ModelLoadResult
import com.debanshu777.caraml.core.data.inference.PromptContextFullException
import com.debanshu777.caraml.core.media.GeneratedMediaStore
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.core.recommendation.InstalledModelLoadResolver
import com.debanshu777.caraml.core.recommendation.LoadAdmission
import com.debanshu777.caraml.core.recommendation.LoadAdmissionReason
import com.debanshu777.caraml.core.recommendation.LoadRequest
import com.debanshu777.caraml.core.recommendation.RiskAcknowledgement
import com.debanshu777.caraml.core.recommendation.RunPlan
import com.debanshu777.caraml.features.chat.data.ChatMessage
import com.debanshu777.caraml.features.chat.data.LiveGenerationStats
import com.debanshu777.caraml.features.chat.data.MessageRole
import com.debanshu777.caraml.features.chat.domain.GenerationMode
import com.debanshu777.caraml.features.chat.domain.filterForMode
import com.debanshu777.caraml.features.chat.domain.matchesGenerationMode
import com.debanshu777.caraml.features.chat.domain.usecase.GenerateResponseUseCase
import com.debanshu777.caraml.features.chat.domain.usecase.GenerationResult
import com.debanshu777.caraml.features.chat.domain.usecase.GetAvailableModelsUseCase
import com.debanshu777.caraml.features.chat.domain.usecase.ContextResetResult
import com.debanshu777.caraml.features.chat.domain.usecase.ManageContextUseCase
import com.debanshu777.caraml.features.chat.domain.usecase.TrackModelUsageUseCase
import com.debanshu777.caraml.core.rating.DiffusionStepPolicy
import com.debanshu777.caraml.core.rating.DistilledHint
import com.debanshu777.caraml.core.rating.SdArchitecture
import com.debanshu777.caraml.core.rating.SdArchitectureClassifier
import com.debanshu777.diffusionrunner.ImageGenParams
import com.debanshu777.diffusionrunner.SampleMethod
import com.debanshu777.diffusionrunner.VideoGenParams
import com.debanshu777.runner.StopReason
import com.debanshu777.huggingfacemanager.sdcpp.SdCppRecommendedParams
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock

private sealed class InternalChatState {
    data object NoModels : InternalChatState()

    data class NoModelsForMode(
        val mode: GenerationMode,
    ) : InternalChatState()

    data object ModelLoading : InternalChatState()

    data class ModelError(
        val message: String,
        val canRetryCurrentModel: Boolean = false,
    ) : InternalChatState()

    data class MissingComponents(
        val missingComponentLabels: List<String>,
        val modelName: String,
        val modelId: String,
    ) : InternalChatState()

    data class LoadActionRequired(val action: PendingLoadAction) : InternalChatState()

    data class ReadyCore(
        val contextLimit: Int,
        val isGenerating: Boolean,
    ) : InternalChatState()
}

sealed interface PendingLoadAction {
    data class ConfirmRisk(val request: LoadRequest) : PendingLoadAction
    data class AcceptAlternative(
        val original: LoadRequest,
        val saferPlan: RunPlan,
        val saferRequest: LoadRequest = original.copy(
            plan = saferPlan,
            riskAcknowledgement = null,
            backendAlternative = null,
        ),
    ) : PendingLoadAction
    data class RetryQuarantined(val request: LoadRequest) : PendingLoadAction
}

private data class ModelLoadAttempt(
    val model: LocalModelEntity,
    val mode: GenerationMode,
    val generation: Long,
)

private data class RunnerTeardownAttempt(
    val generation: Long,
)

private sealed interface RunnerTeardownKey {
    data object NoModels : RunnerTeardownKey
    data class NoModelsForMode(val mode: GenerationMode) : RunnerTeardownKey
    data object UnsupportedVideo : RunnerTeardownKey
    data object Cleared : RunnerTeardownKey
}

@OptIn(ExperimentalAtomicApi::class)
class ChatViewModel(
    getAvailableModels: GetAvailableModelsUseCase,
    private val generateResponse: GenerateResponseUseCase,
    private val manageContext: ManageContextUseCase,
    private val trackModelUsage: TrackModelUsageUseCase,
    private val inferenceRepository: InferenceRepository,
    private val diffusionRepository: DiffusionInferenceRepository,
    private val generatedMediaStore: GeneratedMediaStore,
    private val installedModelLoadRequestResolver: InstalledModelLoadResolver,
    private val modelLoadDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val releaseDiffusionModel: suspend () -> Unit = diffusionRepository::release,
    private val loadDiffusionModel: suspend (LoadRequest) -> ModelLoadResult = diffusionRepository::loadModel,
) : ViewModel() {

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
            is InternalChatState.ModelError -> ChatUiState.ModelError(
                message = internal.message,
                canRetryCurrentModel = internal.canRetryCurrentModel,
            )
            is InternalChatState.MissingComponents -> ChatUiState.MissingComponents(
                missingComponentLabels = internal.missingComponentLabels,
                modelName = internal.modelName,
                modelId = internal.modelId,
            )
            is InternalChatState.LoadActionRequired -> ChatUiState.LoadActionRequired(internal.action)
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
    private val modelLoadGeneration = AtomicLong(0L)
    private val modelLoadOwnership = Mutex()
    private var modelLoadOwnerGeneration = 0L
    private var runnersRequireTeardown = true
    private var lastRunnerTeardownKey: RunnerTeardownKey? = null
    private var lastRunnerTeardownJob: Job? = null
    private var generationJob: Job? = null
    private val pendingLoadActionGate = PendingLoadActionGate()

    private val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        combine(_topModels, _generationMode) { models, mode -> models to mode }
            .onEach { (models, mode) -> ensureSelectionForInventory(models, mode) }
            .launchIn(viewModelScope)

        _selectedModel
            .distinctUntilChanged { old, new -> old?.id == new?.id }
            .filterNotNull()
            .onEach { model -> loadSelectedModel(model) }
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
        if (mode == GenerationMode.Video && !diffusionRepository.supportsVideoGeneration()) {
            transitionWithoutModel(
                state = InternalChatState.ModelError(
                    "Video generation is not available on this platform yet."
                ),
                teardownKey = RunnerTeardownKey.UnsupportedVideo,
            ).join()
            return
        }
        if (models.isEmpty()) {
            transitionWithoutModel(
                state = InternalChatState.NoModels,
                teardownKey = RunnerTeardownKey.NoModels,
            ).join()
            return
        }
        val picker = models.filterForMode(mode)
        if (picker.isEmpty()) {
            transitionWithoutModel(
                state = InternalChatState.NoModelsForMode(mode),
                teardownKey = RunnerTeardownKey.NoModelsForMode(mode),
            ).join()
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
            loadSelectedModel(sel)
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
        signalGenerationCancellation()
        startRunnerTeardown(
            key = RunnerTeardownKey.Cleared,
            scope = teardownScope,
            clearGeneratedMedia = true,
        )
    }

    fun setGenerationMode(mode: GenerationMode) {
        if (_generationMode.value == mode) return
        modelLoadJob?.cancel()
        signalGenerationCancellation()
        generationJob?.cancel()
        _streamingState.value = StreamingState()
        _generationMode.value = mode

        if (mode == GenerationMode.Video && !diffusionRepository.supportsVideoGeneration()) {
            transitionWithoutModel(
                state = InternalChatState.ModelError(
                    "Video generation is not available on this platform yet."
                ),
                teardownKey = RunnerTeardownKey.UnsupportedVideo,
            )
            return
        }

        val models = _topModels.value
        val picker = models.filterForMode(mode)
        if (models.isEmpty()) {
            transitionWithoutModel(
                state = InternalChatState.NoModels,
                teardownKey = RunnerTeardownKey.NoModels,
            )
            return
        }
        if (picker.isEmpty()) {
            transitionWithoutModel(
                state = InternalChatState.NoModelsForMode(mode),
                teardownKey = RunnerTeardownKey.NoModelsForMode(mode),
            )
            return
        }
        val previous = _selectedModel.value
        val next = pickRememberedModel(picker, mode) ?: picker.first()
        _internal.value = InternalChatState.ModelLoading
        _selectedModel.value = next
        if (previous?.id == next.id) {
            loadSelectedModel(next)
        }
    }

    fun selectModel(model: LocalModelEntity) {
        if (!model.matchesGenerationMode(_generationMode.value)) return

        val selectionUnchanged = _selectedModel.value?.id == model.id
        _selectedModel.value = model
        if (selectionUnchanged) loadSelectedModel(model)
        when (_generationMode.value) {
            GenerationMode.Text -> lastTextModelId = model.id
            GenerationMode.Image,
            GenerationMode.Video,
            -> lastDiffusionModelId = model.id
        }
        viewModelScope.launch { trackModelUsage(model) }
    }

    private fun loadSelectedModel(model: LocalModelEntity) {
        startModelLoad(model) { attempt ->
            loadInstalledModel(
                model = model,
                mode = attempt.mode,
                resolve = installedModelLoadRequestResolver::resolve,
                loadText = { request ->
                    loadExactModelForAttempt(attempt, GenerationMode.Text, request)
                },
                loadDiffusion = { request ->
                    loadExactModelForAttempt(attempt, attempt.mode, request)
                },
            )
        }
    }

    private fun startModelLoad(
        model: LocalModelEntity,
        load: suspend (ModelLoadAttempt) -> ModelLoadResult,
    ) {
        val mode = _generationMode.value
        if (!model.matchesGenerationMode(mode)) return

        val previousJob = modelLoadJob
        modelLoadJob?.cancel()
        signalGenerationCancellation()
        generationJob?.cancel()
        _streamingState.value = StreamingState()

        val loadGeneration = modelLoadGeneration.fetchAndAdd(1L) + 1L
        val attempt = ModelLoadAttempt(model, mode, loadGeneration)
        lastRunnerTeardownKey = null
        lastRunnerTeardownJob = null

        modelLoadJob = viewModelScope.launch(modelLoadDispatcher) {
            // Claim first, then release the ownership lock before joining. A successor is therefore
            // current while it waits for its predecessor, without deadlocking that predecessor's exit.
            claimModelLoad(attempt)
            // Wait for the previous job to fully complete (including any in-progress JNI call)
            // before we start new native operations. Without this, a cancelled job that is still
            // inside a blocking JNI call races with our unloadModel() → double-free crash.
            awaitPreviousModelLoad(previousJob)

            val result = load(attempt)
            withContext(Dispatchers.Main.immediate) {
                withCurrentModelLoad(attempt) {
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
                        is ModelLoadResult.AdmissionRequired -> handleAdmission(result.admission)
                    }
                }
            }
        }
    }

    private suspend fun claimModelLoad(attempt: ModelLoadAttempt) {
        withContext(Dispatchers.Main.immediate) {
            modelLoadOwnership.withLock {
                currentCoroutineContext().ensureActive()
                if (!isCurrentModelLoadRequest(attempt)) {
                    throw CancellationException("Stale model load attempt")
                }
                modelLoadOwnerGeneration = attempt.generation
                _internal.value = InternalChatState.ModelLoading
            }
        }
    }

    private fun transitionWithoutModel(
        state: InternalChatState,
        teardownKey: RunnerTeardownKey,
    ): Job {
        val teardownJob = startRunnerTeardown(teardownKey)
        _internal.value = state
        _selectedModel.value = null
        return teardownJob
    }

    private fun startRunnerTeardown(
        key: RunnerTeardownKey,
        scope: CoroutineScope = viewModelScope,
        clearGeneratedMedia: Boolean = false,
    ): Job {
        if (lastRunnerTeardownKey == key) {
            lastRunnerTeardownJob?.takeUnless { it.isCancelled }?.let { return it }
        }

        val previousJob = modelLoadJob
        previousJob?.cancel()
        val generation = modelLoadGeneration.fetchAndAdd(1L) + 1L
        val attempt = RunnerTeardownAttempt(generation)
        val teardownJob = scope.launch(modelLoadDispatcher) {
            try {
                claimRunnerTeardown(attempt)
                awaitPreviousModelLoad(previousJob)
                teardownRunners(attempt)
            } finally {
                if (clearGeneratedMedia) {
                    withContext(kotlinx.coroutines.NonCancellable) {
                        generatedMediaStore.clear()
                    }
                }
            }
        }
        modelLoadJob = teardownJob
        lastRunnerTeardownKey = key
        lastRunnerTeardownJob = teardownJob
        return teardownJob
    }

    private suspend fun claimRunnerTeardown(attempt: RunnerTeardownAttempt) {
        modelLoadOwnership.withLock {
            currentCoroutineContext().ensureActive()
            if (modelLoadGeneration.load() != attempt.generation) {
                throw CancellationException("Stale runner teardown attempt")
            }
            modelLoadOwnerGeneration = attempt.generation
        }
    }

    private suspend fun <T> withCurrentModelLoad(
        attempt: ModelLoadAttempt,
        action: suspend () -> T,
    ): T {
        // The current-owner check and the protected action share one critical section. A newer
        // attempt cannot claim ownership between validation and JNI entry or a terminal state write.
        modelLoadOwnership.lock()
        try {
            currentCoroutineContext().ensureActive()
            if (modelLoadOwnerGeneration != attempt.generation ||
                !isCurrentModelLoadRequest(attempt)
            ) {
                throw CancellationException("Stale model load attempt")
            }
            return action()
        } finally {
            modelLoadOwnership.unlock()
        }
    }

    private suspend fun teardownRunners(attempt: RunnerTeardownAttempt) {
        modelLoadOwnership.lock()
        try {
            currentCoroutineContext().ensureActive()
            if (modelLoadOwnerGeneration != attempt.generation ||
                modelLoadGeneration.load() != attempt.generation
            ) {
                throw CancellationException("Stale runner teardown attempt")
            }
            if (!runnersRequireTeardown) return
            withContext(kotlinx.coroutines.NonCancellable) {
                try {
                    inferenceRepository.unloadModel()
                } finally {
                    releaseDiffusionModel()
                }
                runnersRequireTeardown = false
            }
        } finally {
            modelLoadOwnership.unlock()
        }
    }

    private suspend fun unloadTextRunner(attempt: ModelLoadAttempt) {
        withCurrentModelLoad(attempt) { inferenceRepository.unloadModel() }
    }

    private suspend fun releaseDiffusionRunner(attempt: ModelLoadAttempt) {
        withCurrentModelLoad(attempt) { releaseDiffusionModel() }
    }

    private suspend fun loadTextRunner(
        attempt: ModelLoadAttempt,
        request: LoadRequest,
    ): ModelLoadResult = withCurrentModelLoad(attempt) {
        runnersRequireTeardown = true
        inferenceRepository.loadModel(request)
    }

    private suspend fun loadDiffusionRunner(
        attempt: ModelLoadAttempt,
        request: LoadRequest,
    ): ModelLoadResult = withCurrentModelLoad(attempt) {
        runnersRequireTeardown = true
        loadDiffusionModel(request)
    }

    private fun isCurrentModelLoadRequest(attempt: ModelLoadAttempt): Boolean =
        modelLoadGeneration.load() == attempt.generation &&
            _selectedModel.value == attempt.model &&
            _generationMode.value == attempt.mode

    private suspend fun loadExactModelForAttempt(
        attempt: ModelLoadAttempt,
        mode: GenerationMode,
        request: LoadRequest,
    ): ModelLoadResult = loadExactModelForMode(
        mode = mode,
        request = request,
        unloadText = { unloadTextRunner(attempt) },
        releaseDiffusion = { releaseDiffusionRunner(attempt) },
        loadText = { exact -> loadTextRunner(attempt, exact) },
        loadDiffusion = { exact -> loadDiffusionRunner(attempt, exact) },
    )

    private fun handleAdmission(admission: LoadAdmission) {
        pendingLoadActionGate.close()
        when (admission) {
            is LoadAdmission.ConfirmationRequired -> {
                val action = if (admission.explicitRetryRequired) {
                    PendingLoadAction.RetryQuarantined(admission.request)
                } else {
                    PendingLoadAction.ConfirmRisk(admission.request)
                }
                pendingLoadActionGate.open()
                _internal.value = InternalChatState.LoadActionRequired(action)
            }
            is LoadAdmission.AlternativeAvailable -> {
                pendingLoadActionGate.open()
                _internal.value = InternalChatState.LoadActionRequired(
                    PendingLoadAction.AcceptAlternative(
                        admission.original,
                        admission.saferPlan,
                        admission.saferRequest,
                    ),
                )
            }
            is LoadAdmission.TemporarilyUnavailable -> {
                _internal.value = InternalChatState.ModelError(
                    "The device is under memory or thermal pressure. Try again after it recovers.",
                )
            }
            is LoadAdmission.Blocked -> {
                _internal.value = InternalChatState.ModelError(
                    admission.reason.safeBlockedLoadMessage(),
                    canRetryCurrentModel =
                        admission.reason == LoadAdmissionReason.NATIVE_PREFLIGHT_INVALID,
                )
            }
            is LoadAdmission.Ready -> resumeExactLoad(admission.request)
        }
    }

    fun confirmPendingLoad() {
        val action = (_internal.value as? InternalChatState.LoadActionRequired)?.action
            as? PendingLoadAction.ConfirmRisk ?: return
        if (!pendingLoadActionGate.tryConsume()) return
        val now = Clock.System.now().toEpochMilliseconds()
        resumeExactLoad(
            action.request.copy(
                riskAcknowledgement = RiskAcknowledgement(
                    action.request.assessmentKey,
                    action.request.plan.stableKey,
                    now,
                ),
            ),
        )
    }

    fun acceptSaferPlan() {
        val action = (_internal.value as? InternalChatState.LoadActionRequired)?.action
            as? PendingLoadAction.AcceptAlternative ?: return
        if (!pendingLoadActionGate.tryConsume()) return
        resumeExactLoad(action.saferRequest.copy(riskAcknowledgement = null, backendAlternative = null))
    }

    fun retryPendingLoad() {
        val action = (_internal.value as? InternalChatState.LoadActionRequired)?.action
            as? PendingLoadAction.RetryQuarantined ?: return
        if (!pendingLoadActionGate.tryConsume()) return
        if (_selectedModel.value != action.request.model) return
        startModelLoad(action.request.model) { attempt ->
            retryQuarantinedLoad(action.request, attempt)
        }
    }

    private suspend fun retryQuarantinedLoad(
        request: LoadRequest,
        attempt: ModelLoadAttempt,
    ): ModelLoadResult {
        try {
            when (request.plan) {
                is com.debanshu777.caraml.core.recommendation.LlmRunPlan ->
                    inferenceRepository.allowExplicitRetry(request)
                is com.debanshu777.caraml.core.recommendation.DiffusionRunPlan ->
                    diffusionRepository.allowExplicitRetry(request)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return ModelLoadResult.Error("The model cannot be retried right now.")
        }
        return loadExactModelForAttempt(
            attempt = attempt,
            mode = attempt.mode,
            request = request.copy(riskAcknowledgement = null),
        )
    }

    fun cancelPendingLoad() {
        if (_internal.value !is InternalChatState.LoadActionRequired) return
        if (!pendingLoadActionGate.tryConsume()) return
        _internal.value = InternalChatState.ModelError("Model loading was cancelled.")
    }

    fun retryCurrentModel() {
        val failure = _internal.value as? InternalChatState.ModelError ?: return
        if (!failure.canRetryCurrentModel) return
        val model = _selectedModel.value ?: return
        loadSelectedModel(model)
    }

    private fun resumeExactLoad(request: LoadRequest) {
        startModelLoad(request.model) { attempt ->
            loadExactModelForAttempt(attempt, attempt.mode, request)
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
            } catch (error: PromptContextFullException) {
                finalizeWithError(assistantMessage.id, error.message.orEmpty())
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
                val sampler = SampleMethod.fromName(rp?.samplingMethod)
                val params = ImageGenParams(
                    prompt = prompt,
                    negativePrompt = negative,
                    width = rp?.width ?: 512,
                    height = rp?.height ?: 512,
                    steps = rp?.steps ?: resolveDefaultSteps(sampler),
                    cfgScale = rp?.cfgScale ?: 7f,
                    // Use registry-pinned seed when available; otherwise roll a new random seed.
                    seed = rp?.seed ?: Clock.System.now().toEpochMilliseconds(),
                    sampleMethod = sampler,
                )
                val result = diffusionRepository.generateImage(params)
                val bytes = result.getOrElse { throw it }
                if (bytes.isEmpty()) {
                    finalizeWithError(assistantMessage.id)
                } else {
                    val imagePath = generatedMediaStore.saveImage(assistantMessage.id, bytes)
                    finalizeMediaMessage(assistantMessage.id, imagePath = imagePath)
                }
            } catch (_: CancellationException) {
            } catch (error: DiffusionMemoryException) {
                finalizeWithError(assistantMessage.id, error.message.orEmpty())
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
                val sampler = SampleMethod.fromName(rp?.samplingMethod)
                val params = VideoGenParams(
                    prompt = prompt,
                    negativePrompt = negative,
                    width = rp?.width ?: 512,
                    height = rp?.height ?: 512,
                    videoFrames = 16,
                    steps = rp?.steps ?: resolveDefaultSteps(sampler),
                    cfgScale = rp?.cfgScale ?: 7f,
                    // Use registry-pinned seed when available; otherwise roll a new random seed.
                    seed = rp?.seed ?: Clock.System.now().toEpochMilliseconds(),
                    sampleMethod = sampler,
                )
                val result = diffusionRepository.generateVideo(params)
                val frames = result.getOrElse { throw it }
                if (frames.isEmpty()) {
                    finalizeWithError(assistantMessage.id)
                } else {
                    val framePaths = generatedMediaStore.saveVideo(assistantMessage.id, frames)
                    finalizeMediaMessage(assistantMessage.id, videoFramePaths = framePaths)
                }
            } catch (_: CancellationException) {
            } catch (error: DiffusionMemoryException) {
                finalizeWithError(assistantMessage.id, error.message.orEmpty())
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

    private fun resolveDefaultSteps(sampler: SampleMethod): Int {
        val model = _selectedModel.value ?: return 20
        val arch = diffusionRepository.getLastLoadedArchitecture()
            ?.let { SdArchitecture.fromNativeString(it) }
            ?: SdArchitectureClassifier.classify(emptyList(), model.modelId)
        val distilled = if (SdArchitectureClassifier.isDistilled(model.modelId))
            DistilledHint.YES else DistilledHint.UNKNOWN
        return DiffusionStepPolicy.recommend(arch, sampler, distilled).steps
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
        imagePath: String? = null,
        videoFramePaths: List<String>? = null,
    ) {
        _messages.update { list ->
            val messages = list.toMutableList()
            val idx = messages.indexOfLast { it.id == assistantMessageId }
            if (idx >= 0) {
                messages[idx] = messages[idx].copy(
                    text = "",
                    imagePath = imagePath,
                    videoFramePaths = videoFramePaths,
                )
            }
            messages.toImmutableList()
        }
        updateReadyCore { it.copy(isGenerating = false) }
        _streamingState.value = StreamingState()
    }

    private fun finalizeWithError(
        assistantMessageId: String,
        message: String = "Something went wrong. Please try again.",
    ) {
        _messages.update { list ->
            val messages = list.toMutableList()
            val idx = messages.indexOfLast { it.id == assistantMessageId }
            if (idx >= 0) {
                messages[idx] = messages[idx].copy(
                    text = message,
                )
            }
            messages.toImmutableList()
        }
        updateReadyCore { it.copy(isGenerating = false) }
        _streamingState.value = StreamingState()
    }

    private suspend fun handleContextReset(messages: List<ChatMessage>) {
        val progressMessageId = addProgressMessage("Chat summarization in progress")
        val status = when (manageContext.resetContext(messages)) {
            ContextResetResult.Success -> "Chat summarized"
            ContextResetResult.Failure -> "Could not reset chat context"
        }
        updateProgressMessage(progressMessageId, status)
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
        signalGenerationCancellation()
        generationJob?.cancel()
    }

    suspend fun loadGeneratedMedia(path: String): ByteArray? = generatedMediaStore.read(path)

    private fun signalGenerationCancellation() {
        when (_generationMode.value) {
            GenerationMode.Text -> inferenceRepository.cancelGeneration()
            GenerationMode.Image,
            GenerationMode.Video,
            -> diffusionRepository.cancelGeneration()
        }
    }
}
