package com.debanshu777.caraml.features.chat.presentation

import com.debanshu777.caraml.core.data.inference.DiffusionInferenceRepository
import com.debanshu777.caraml.core.data.inference.InferenceRepository
import com.debanshu777.caraml.core.data.inference.ModelLoadResult
import com.debanshu777.caraml.core.data.settings.SettingsRepository
import com.debanshu777.caraml.core.media.GeneratedMediaStore
import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.DeviceCapabilities
import com.debanshu777.caraml.core.platform.MemoryTopology
import com.debanshu777.caraml.core.recommendation.AssessedPlans
import com.debanshu777.caraml.core.recommendation.AssessmentConfidence
import com.debanshu777.caraml.core.recommendation.Confidence
import com.debanshu777.caraml.core.recommendation.InferenceObservationPlan
import com.debanshu777.caraml.core.recommendation.InstalledModelLoadResolution
import com.debanshu777.caraml.core.recommendation.InstalledModelLoadPreparation
import com.debanshu777.caraml.core.recommendation.InstalledModelLoadResolver
import com.debanshu777.caraml.core.recommendation.KvCacheType
import com.debanshu777.caraml.core.recommendation.LlmRunPlan
import com.debanshu777.caraml.core.recommendation.LoadAdmission
import com.debanshu777.caraml.core.recommendation.LoadAdmissionReason
import com.debanshu777.caraml.core.recommendation.LoadRequest
import com.debanshu777.caraml.core.recommendation.ObservationModelIdentity
import com.debanshu777.caraml.core.recommendation.PlanAssessment
import com.debanshu777.caraml.core.recommendation.RecommendationProfile
import com.debanshu777.caraml.core.recommendation.RepositoryCommit
import com.debanshu777.caraml.core.recommendation.ResolvedArtifactComponent
import com.debanshu777.caraml.core.recommendation.canonicalDownloadRemoteObjectId
import com.debanshu777.caraml.core.recommendation.ResolvedLocalArtifact
import com.debanshu777.caraml.core.recommendation.RevisionIdentity
import com.debanshu777.caraml.core.recommendation.VerifiedArtifactLoadTarget
import com.debanshu777.caraml.core.recommendation.task6LlmDescriptor
import com.debanshu777.caraml.core.settings.AppSettings
import com.debanshu777.caraml.core.storage.localmodel.LocalModelDao
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.core.storage.localmodel.LocalModelRepository
import com.debanshu777.caraml.features.chat.domain.ChatConfig
import com.debanshu777.caraml.features.chat.data.MessageDelivery
import com.debanshu777.caraml.features.chat.domain.GenerationMode
import com.debanshu777.caraml.features.chat.domain.usecase.GenerateResponseUseCase
import com.debanshu777.caraml.features.chat.domain.usecase.GetAvailableModelsUseCase
import com.debanshu777.caraml.features.chat.domain.usecase.ManageContextUseCase
import com.debanshu777.caraml.features.chat.domain.usecase.TrackModelUsageUseCase
import com.debanshu777.diffusionrunner.DiffusionRunner
import com.debanshu777.runner.InferenceChunk
import com.debanshu777.runner.StopReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelRetryTest {
    @Test
    fun tokenAndContextLimitsPreserveTextAndNeverReportAnEmptyAnswerAsComplete() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val model = textModel()
            val request = loadRequest(model, "fresh-cpu", BackendKind.CPU)
            val inference = RecordingInferenceRepository(request).apply { aboveThreshold = false }
            val localModels = LocalModelRepository(StaticLocalModelDao(model))
            val viewModel = ChatViewModel(
                getAvailableModels = GetAvailableModelsUseCase(localModels, ChatConfig()),
                generateResponse = GenerateResponseUseCase(inference),
                manageContext = ManageContextUseCase(inference, ChatConfig()),
                trackModelUsage = TrackModelUsageUseCase(localModels),
                inferenceRepository = inference,
                diffusionRepository = DiffusionInferenceRepository(DiffusionRunner(), DeviceCapabilities(), StaticSettingsRepository()),
                generatedMediaStore = GeneratedMediaStore(baseDirectory = "/tmp", sessionId = "reply-limit"),
                installedModelLoadRequestResolver = RecordingResolver(ArrayDeque(listOf(InstalledModelLoadResolution.Ready(request)))),
                modelLoadDispatcher = dispatcher,
                releaseDiffusionModel = {},
            )
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }
            advanceUntilIdle()
            val cases = listOf(
                Triple(StopReason.MAX_TOKENS, "", "TokenLimit"),
                Triple(StopReason.MAX_TOKENS, "Partial answer", "TokenLimit"),
                Triple(StopReason.CONTEXT_FULL, "Partial answer", "ContextLimit"),
                Triple(StopReason.EOG, "", "NoAnswer"),
                Triple(StopReason.EOG, "  ", "NoAnswer"),
                Triple(StopReason.EOG, "Complete answer", "Complete"),
            )
            var messageCount = 0
            for ((stop, answer, expected) in cases) {
                inference.responseStopReason = stop
                inference.response = flow { emit(InferenceChunk(reasoningDelta = "Model reasoning", contentDelta = answer)) }
                viewModel.sendMessage("Explain dark matter")
                messageCount += 2
                val terminal = assertIs<ChatUiState.Ready>(viewModel.uiState.first { it is ChatUiState.Ready && !it.isGenerating && it.messages.size == messageCount }).messages.last()
                assertEquals(expected, terminal.delivery?.name)
                assertEquals(answer, terminal.text)
                assertEquals("Model reasoning", terminal.thinking)
            }
        } finally { Dispatchers.resetMain() }
    }


    @Test
    fun compressionOwnsTheTurnBeforeSuspendingAndPreservesTheSubmittedPrompt() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val model = textModel()
            val request = loadRequest(model, "fresh-cpu", BackendKind.CPU)
            val inference = RecordingInferenceRepository(request).apply {
                resetGate = CompletableDeferred()
            }
            val localModels = LocalModelRepository(StaticLocalModelDao(model))
            val viewModel = ChatViewModel(
                getAvailableModels = GetAvailableModelsUseCase(localModels, ChatConfig()),
                generateResponse = GenerateResponseUseCase(inference),
                manageContext = ManageContextUseCase(inference, ChatConfig()),
                trackModelUsage = TrackModelUsageUseCase(localModels),
                inferenceRepository = inference,
                diffusionRepository = DiffusionInferenceRepository(
                    runner = DiffusionRunner(),
                    deviceCapabilities = DeviceCapabilities(),
                    settingsRepository = StaticSettingsRepository(),
                ),
                generatedMediaStore = GeneratedMediaStore(baseDirectory = "/tmp", sessionId = "compression-admission"),
                installedModelLoadRequestResolver = RecordingResolver(
                    ArrayDeque(listOf(InstalledModelLoadResolution.Ready(request))),
                ),
                modelLoadDispatcher = dispatcher,
                releaseDiffusionModel = {},
            )
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }
            advanceUntilIdle()
            val prompt = "explain ADMX in more details"
            viewModel.sendMessage(prompt)
            runCurrent()
            val busy = assertIs<ChatUiState.Ready>(viewModel.uiState.value)
            assertTrue(busy.isGenerating, "Compression must reserve the turn before it suspends")
            assertEquals(listOf(prompt), busy.messages.filter { it.role == com.debanshu777.caraml.features.chat.data.MessageRole.User }.map { it.text })
            viewModel.sendMessage("e")
            runCurrent()
            assertEquals(1, inference.resetCalls)
            inference.resetGate!!.complete(Unit)
            val finished = assertIs<ChatUiState.Ready>(
                viewModel.uiState.first { it is ChatUiState.Ready && !it.isGenerating },
            )
            assertEquals(listOf(prompt), inference.prompts)
            assertEquals(false, finished.isGenerating)
            assertEquals(2, finished.messages.size, "Compression status must not become chat history")
            assertEquals(MessageDelivery.Complete, finished.messages.last().delivery)

            // The reservation must also cover compression after a context-full response.
            inference.aboveThreshold = false
            inference.responseStopReason = StopReason.CONTEXT_FULL
            inference.resetGate = CompletableDeferred()
            inference.resetStarted = CompletableDeferred()
            viewModel.sendMessage("follow-up")
            inference.resetStarted.await()
            runCurrent()
            assertTrue(assertIs<ChatUiState.Ready>(viewModel.uiState.value).isGenerating)
            assertTrue(viewModel.streamingState.value.isCompacting)
            viewModel.sendMessage("must not interleave")
            inference.resetGate!!.complete(Unit)
            viewModel.uiState.first { it is ChatUiState.Ready && !it.isGenerating }
            assertEquals(listOf(prompt, "follow-up"), inference.prompts)
            assertEquals(2, inference.resetCalls)

            viewModel.sendMessage("cancel before the coroutine starts")
            viewModel.cancelGeneration()
            runCurrent()
            assertEquals(false, assertIs<ChatUiState.Ready>(viewModel.uiState.value).isGenerating)
            assertEquals(
                MessageDelivery.Stopped,
                assertIs<ChatUiState.Ready>(viewModel.uiState.value).messages.last().delivery,
            )
            assertEquals(listOf(prompt, "follow-up"), inference.prompts)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun interruptionPathsPreservePartialRepliesAndErrorsHaveTheirOwnDeliveryState() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val model = textModel()
            val request = loadRequest(model, "fresh-cpu", BackendKind.CPU)
            val inference = RecordingInferenceRepository(request).apply {
                response = flow {
                    emit(InferenceChunk(reasoningDelta = "Let me think", contentDelta = "A little answer"))
                    awaitCancellation()
                }
            }
            val localModels = LocalModelRepository(StaticLocalModelDao(model))
            val viewModel = ChatViewModel(
                getAvailableModels = GetAvailableModelsUseCase(localModels, ChatConfig()),
                generateResponse = GenerateResponseUseCase(inference),
                manageContext = ManageContextUseCase(inference, ChatConfig()),
                trackModelUsage = TrackModelUsageUseCase(localModels),
                inferenceRepository = inference,
                diffusionRepository = DiffusionInferenceRepository(
                    runner = DiffusionRunner(),
                    deviceCapabilities = DeviceCapabilities(),
                    settingsRepository = StaticSettingsRepository(),
                ),
                generatedMediaStore = GeneratedMediaStore(baseDirectory = "/tmp", sessionId = "message-delivery"),
                installedModelLoadRequestResolver = RecordingResolver(
                    ArrayDeque(List(3) { InstalledModelLoadResolution.Ready(request) }),
                ),
                modelLoadDispatcher = dispatcher,
                releaseDiffusionModel = {},
            )
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }
            advanceUntilIdle()

            viewModel.sendMessage("Start a reply")
            viewModel.streamingState.first { it.streamingText == "A little answer" }
            viewModel.cancelGeneration()
            val stopped = assertIs<ChatUiState.Ready>(
                viewModel.uiState.first { it is ChatUiState.Ready && !it.isGenerating },
            ).messages.last()
            assertEquals(MessageDelivery.Stopped, stopped.delivery)
            assertEquals("A little answer", stopped.text)
            assertEquals("Let me think", stopped.thinking)
            assertEquals(null, viewModel.streamingState.value.streamingMessageId)

            inference.response = flow { error("Test generation failure") }
            viewModel.sendMessage("Try again")
            val failed = assertIs<ChatUiState.Ready>(
                viewModel.uiState.first { it is ChatUiState.Ready && !it.isGenerating && it.messages.size == 4 },
            )
            assertEquals(MessageDelivery.Error, failed.messages.last().delivery)
            assertEquals("Something went wrong. Please try again.", failed.messages.last().text)
            assertEquals(stopped, failed.messages[1], "A new turn must not replace the stopped reply")

            inference.response = flow {
                emit(InferenceChunk(reasoningDelta = "More thinking", contentDelta = "Another partial reply"))
                awaitCancellation()
            }
            viewModel.sendMessage("Change the loaded model")
            viewModel.streamingState.first { it.streamingText == "Another partial reply" }
            viewModel.selectModel(model)
            val reloaded = assertIs<ChatUiState.Ready>(
                viewModel.uiState.first { it is ChatUiState.Ready && !it.isGenerating && it.messages.size == 6 },
            )
            assertEquals(MessageDelivery.Stopped, reloaded.messages.last().delivery)
            assertEquals("Another partial reply", reloaded.messages.last().text)
            assertEquals("More thinking", reloaded.messages.last().thinking)

            viewModel.sendMessage("Switch the creation mode")
            viewModel.streamingState.first { it.streamingText == "Another partial reply" }
            viewModel.setGenerationMode(GenerationMode.Image)
            viewModel.uiState.first { it is ChatUiState.NoModelsForMode }
            viewModel.setGenerationMode(GenerationMode.Text)
            val restored = assertIs<ChatUiState.Ready>(
                viewModel.uiState.first { it is ChatUiState.Ready && !it.isGenerating && it.messages.size == 8 },
            )
            assertEquals(MessageDelivery.Stopped, restored.messages.last().delivery)
            assertEquals("Another partial reply", restored.messages.last().text)
            assertEquals("More thinking", restored.messages.last().thinking)

            val failAfterPartialOutput = CompletableDeferred<Unit>()
            inference.response = flow {
                emit(InferenceChunk(reasoningDelta = "Useful reasoning", contentDelta = "Keep this partial output"))
                failAfterPartialOutput.await()
                error("Failure after the first output")
            }
            viewModel.sendMessage("Fail after some output")
            viewModel.streamingState.first { it.streamingText == "Keep this partial output" }
            failAfterPartialOutput.complete(Unit)
            val partialFailure = assertIs<ChatUiState.Ready>(
                viewModel.uiState.first { it is ChatUiState.Ready && !it.isGenerating && it.messages.size == 10 },
            ).messages.last()
            assertEquals(MessageDelivery.Error, partialFailure.delivery)
            assertEquals("Keep this partial output", partialFailure.text)
            assertEquals("Useful reasoning", partialFailure.thinking)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun postAssessmentCpuAlternativeWaitsForExplicitUserAcceptance() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val model = textModel()
            val cpuRequest = loadRequest(model, "post-release-cpu", BackendKind.CPU)
            val resolver = RecordingResolver(
                ArrayDeque(
                    listOf(
                        InstalledModelLoadResolution.SafeAlternative(
                            primaryReason = com.debanshu777.caraml.core.recommendation.AssessmentReason.MEMORY_NO_FIT,
                            saferRequest = cpuRequest,
                        ),
                    ),
                ),
            )
            val inference = RecordingInferenceRepository(cpuRequest)
            val localModels = LocalModelRepository(StaticLocalModelDao(model))
            val settings = StaticSettingsRepository()
            val viewModel = ChatViewModel(
                getAvailableModels = GetAvailableModelsUseCase(localModels, ChatConfig()),
                generateResponse = GenerateResponseUseCase(inference),
                manageContext = ManageContextUseCase(inference, ChatConfig()),
                trackModelUsage = TrackModelUsageUseCase(localModels),
                inferenceRepository = inference,
                diffusionRepository = DiffusionInferenceRepository(
                    runner = DiffusionRunner(),
                    deviceCapabilities = DeviceCapabilities(),
                    settingsRepository = settings,
                ),
                generatedMediaStore = GeneratedMediaStore(
                    baseDirectory = "/tmp",
                    sessionId = "post-release-cpu-alternative",
                ),
                installedModelLoadRequestResolver = resolver,
                modelLoadDispatcher = dispatcher,
                releaseDiffusionModel = {},
            )
            val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.uiState.collect()
            }

            advanceUntilIdle()

            val action = assertIs<PendingLoadAction.AcceptSafeAlternative>(
                assertIs<ChatUiState.LoadActionRequired>(viewModel.uiState.value).action,
            )
            assertEquals(cpuRequest, action.saferRequest)
            assertEquals(emptyList(), inference.loadedAssessmentKeys)

            viewModel.acceptSaferPlan()
            advanceUntilIdle()

            assertIs<ChatUiState.Ready>(viewModel.uiState.value)
            assertEquals(listOf("post-release-cpu"), inference.loadedAssessmentKeys)
            collection.cancel()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun retryCurrentModelResolvesFreshRequestAndNeverReusesTerminalRequest() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val model = textModel()
            val cpuRequest = loadRequest(model, "fresh-cpu", BackendKind.CPU)
            val freshRequest = loadRequest(model, "fresh-gpu", BackendKind.VULKAN).copy(
                backendAlternative = cpuRequest,
            )
            val restoredRequest = loadRequest(model, "restored", BackendKind.VULKAN)
            val resolver = RecordingResolver(
                ArrayDeque(
                    listOf(
                        InstalledModelLoadResolution.Ready(restoredRequest),
                        InstalledModelLoadResolution.Ready(freshRequest),
                    ),
                ),
            )
            val inference = RecordingInferenceRepository(cpuRequest)
            val localModels = LocalModelRepository(StaticLocalModelDao(model))
            val settings = StaticSettingsRepository()
            val viewModel = ChatViewModel(
                getAvailableModels = GetAvailableModelsUseCase(localModels, ChatConfig()),
                generateResponse = GenerateResponseUseCase(inference),
                manageContext = ManageContextUseCase(inference, ChatConfig()),
                trackModelUsage = TrackModelUsageUseCase(localModels),
                inferenceRepository = inference,
                diffusionRepository = DiffusionInferenceRepository(
                    runner = DiffusionRunner(),
                    deviceCapabilities = DeviceCapabilities(),
                    settingsRepository = settings,
                ),
                generatedMediaStore = GeneratedMediaStore(
                    baseDirectory = "/tmp",
                    sessionId = "restored-model-retry",
                ),
                installedModelLoadRequestResolver = resolver,
                modelLoadDispatcher = dispatcher,
                releaseDiffusionModel = {},
            )
            val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.uiState.collect()
            }

            advanceUntilIdle()

            val terminal = assertIs<ChatUiState.ModelError>(viewModel.uiState.value)
            assertTrue(terminal.canRetryCurrentModel)
            assertEquals(listOf("restored"), inference.loadedAssessmentKeys)
            assertEquals(1, resolver.calls)

            viewModel.retryCurrentModel()
            viewModel.retryCurrentModel()
            advanceUntilIdle()

            val action = assertIs<ChatUiState.LoadActionRequired>(viewModel.uiState.value).action
            val alternative = assertIs<PendingLoadAction.AcceptAlternative>(action)
            assertEquals("fresh-gpu", alternative.original.assessmentKey)
            assertEquals("fresh-cpu", alternative.saferRequest.assessmentKey)
            assertEquals(2, resolver.calls)
            assertEquals(listOf("restored", "fresh-gpu"), inference.loadedAssessmentKeys)

            viewModel.acceptSaferPlan()
            advanceUntilIdle()

            assertIs<ChatUiState.Ready>(viewModel.uiState.value)
            assertEquals(
                listOf("restored", "fresh-gpu", "fresh-cpu"),
                inference.loadedAssessmentKeys,
            )
            assertEquals(1, inference.loadedAssessmentKeys.count { it == "restored" })
            assertEquals(2, resolver.calls)
            collection.cancel()
        } finally {
            Dispatchers.resetMain()
        }
    }
}

private class RecordingResolver(
    private val resolutions: ArrayDeque<InstalledModelLoadResolution>,
) : InstalledModelLoadResolver {
    var calls: Int = 0
        private set

    override suspend fun prepare(
        model: LocalModelEntity,
        expectedMode: GenerationMode,
    ): InstalledModelLoadPreparation {
        assertEquals("owner/model", model.modelId)
        assertEquals(GenerationMode.Text, expectedMode)
        val request = when (val next = resolutions.first()) {
            is InstalledModelLoadResolution.Ready -> next.request
            is InstalledModelLoadResolution.SafeAlternative -> next.saferRequest
            else -> error("This fixture requires exact prepared evidence")
        }
        return InstalledModelLoadPreparation.Ready(
            model = model,
            expectedMode = expectedMode,
            descriptor = task6LlmDescriptor(),
            artifact = requireNotNull(request.artifact),
        )
    }

    override suspend fun resolve(
        preparation: InstalledModelLoadPreparation.Ready,
    ): InstalledModelLoadResolution {
        calls += 1
        return resolutions.removeFirst()
    }
}

private class RecordingInferenceRepository(
    private val saferRequest: LoadRequest,
) : InferenceRepository {
    val loadedAssessmentKeys = mutableListOf<String>()
    var resetGate: CompletableDeferred<Unit>? = null
    var resetCalls = 0
    var aboveThreshold = true
    var responseStopReason = 0
    var resetStarted = CompletableDeferred<Unit>()
    var response: Flow<InferenceChunk> = flow { emit(InferenceChunk(reasoningDelta = "", contentDelta = "A complete answer")) }
    val prompts = mutableListOf<String>()

    override suspend fun loadModel(request: LoadRequest): ModelLoadResult {
        loadedAssessmentKeys += request.assessmentKey
        return when (request.assessmentKey) {
            "restored" -> ModelLoadResult.AdmissionRequired(
                LoadAdmission.Blocked(request, LoadAdmissionReason.NATIVE_PREFLIGHT_INVALID),
            )
            "fresh-gpu" -> ModelLoadResult.AdmissionRequired(
                LoadAdmission.AlternativeAvailable(
                    original = request,
                    saferPlan = saferRequest.plan,
                    reason = LoadAdmissionReason.NATIVE_BACKEND_INCOMPATIBLE,
                    saferRequest = saferRequest,
                ),
            )
            "fresh-cpu" -> ModelLoadResult.Success(contextSize = 4_096)
            "post-release-cpu" -> ModelLoadResult.Success(contextSize = 4_096)
            else -> error("Unexpected request")
        }
    }

    override suspend fun unloadModel() = Unit
    override fun generateResponse(userPrompt: String): Flow<InferenceChunk> {
        prompts += userPrompt
        return response
    }
    override fun cancelGeneration() = Unit
    override fun getContextUsed(): Int = 0
    override fun getContextLimit(): Int = 4_096
    override fun getStopReason(): Int = responseStopReason
    override fun isContextAboveThreshold(): Boolean = aboveThreshold && resetGate != null
    override fun summarizeConversation(transcript: String): Flow<String> = emptyFlow()
    override suspend fun resetContextWithSummary(summary: String, lastExchange: String): Boolean {
        resetCalls++
        resetStarted.complete(Unit)
        resetGate?.await()
        return true
    }
    override suspend fun resetContext() = Unit
    override fun getRuntimeConfigString(): String = ""
    override fun currentGenerationObservation(): InferenceObservationPlan? = null
}

private class StaticSettingsRepository : SettingsRepository {
    private val settings = MutableStateFlow(AppSettings())

    override fun getSettings(): Flow<AppSettings> = settings
    override suspend fun updateSettings(settings: AppSettings) {
        this.settings.value = settings
    }
    override suspend fun updateRecommendationProfile(profile: RecommendationProfile) = Unit
    override suspend fun completeModelProfileOnboarding(profile: RecommendationProfile) = Unit
}

private class StaticLocalModelDao(
    private val model: LocalModelEntity,
) : LocalModelDao {
    private val models = MutableStateFlow(listOf(model))

    override suspend fun getFilenamesByModelId(modelId: String): List<String> = listOf(model.filename)
    override suspend fun deleteByModelIdAndFilename(modelId: String, filename: String) = Unit
    override suspend fun deleteAllForModelId(modelId: String) = Unit
    override suspend fun insert(entity: LocalModelEntity) = Unit
    override fun getAllDownloadedFiles(): Flow<List<LocalModelEntity>> = models
    override fun getDownloadedFilesByType(modelType: String): Flow<List<LocalModelEntity>> = models
    override suspend fun incrementUsageCount(modelId: String, filename: String) = Unit
    override fun getTotalDownloadedSizeBytes(): Flow<Long> = MutableStateFlow(model.sizeBytes ?: 0L)
    override suspend fun updateComponentStatus(modelId: String, status: String) = Unit
    override suspend fun getMainModels(): List<LocalModelEntity> = listOf(model)
    override suspend fun updateArch(modelId: String, arch: String) = Unit
    override suspend fun demoteMmprojFilesFromMain() = Unit
}

private fun textModel() = LocalModelEntity(
    id = 7,
    modelId = "owner/model",
    filename = "model.gguf",
    localPath = "/private/model.gguf",
    sizeBytes = 4,
    downloadedAt = 1,
    author = "owner",
    libraryName = "gguf",
    pipelineTag = "text-generation",
    componentStatus = LocalModelEntity.STATUS_READY,
)

private fun loadRequest(
    model: LocalModelEntity,
    assessmentKey: String,
    backend: BackendKind,
): LoadRequest {
    val descriptor = task6LlmDescriptor()
    val identity = descriptor.file
    val plan = LlmRunPlan(
        contextTokens = 4_096,
        batchSize = 128,
        microBatchSize = 64,
        sequenceCount = 1,
        keyCacheType = KvCacheType.F16,
        valueCacheType = KvCacheType.F16,
        backend = backend,
        memoryTopology = MemoryTopology.UNIFIED,
        gpuLayerCount = if (backend == BackendKind.CPU) 0 else 24,
        compromises = emptyList(),
    )
    return LoadRequest(
        model = model,
        identity = identity,
        observationIdentity = requireNotNull(ObservationModelIdentity.fromDescriptor(descriptor)),
        plan = plan,
        assessmentKey = assessmentKey,
        artifact = ResolvedLocalArtifact(
            identity = identity,
            revisionIdentity = RevisionIdentity.HubCommit(
                listOf(RepositoryCommit(model.modelId, identity.revision)),
            ),
            components = listOf(
                ResolvedArtifactComponent(
                    logicalRole = "model",
                    repositoryId = model.modelId,
                    repositoryRelativePath = identity.path,
                    localPath = model.localPath,
                    byteCount = identity.sizeBytes,
                    contentSha256 = "c".repeat(64),
                    identity = identity,
                    remoteObjectId = requireNotNull(identity.canonicalDownloadRemoteObjectId()),
                    storageRoot = model.localPath.substringBeforeLast('/'),
                ),
            ),
            loadTarget = VerifiedArtifactLoadTarget.File(
                path = model.localPath,
                componentRole = "model",
                repositoryId = model.modelId,
                localRelativePath = identity.path,
            ),
        ),
        assessedPlans = AssessedPlans(
            values = listOf(
                PlanAssessment(
                    plan = plan,
                    hostMemoryBytes = null,
                    gpuMemoryBytes = null,
                    sharedMemoryBytes = null,
                    storageBytes = null,
                    confidence = AssessmentConfidence(
                        Confidence.LOW,
                        Confidence.LOW,
                        Confidence.LOW,
                        Confidence.LOW,
                    ),
                    evidence = emptyList(),
                ),
            ),
            assessmentKey = assessmentKey,
        ),
    )
}
