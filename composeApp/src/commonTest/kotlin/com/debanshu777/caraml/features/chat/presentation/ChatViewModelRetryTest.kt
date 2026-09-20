package com.debanshu777.caraml.features.chat.presentation

import com.debanshu777.caraml.core.data.inference.DiffusionInferenceRepository
import com.debanshu777.caraml.core.data.inference.InferenceRepository
import com.debanshu777.caraml.core.data.inference.ModelLoadResult
import com.debanshu777.caraml.core.data.settings.SettingsRepository
import com.debanshu777.caraml.core.media.GeneratedMediaStore
import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.DeviceCapabilities
import com.debanshu777.caraml.core.platform.MemoryTopology
import com.debanshu777.caraml.core.recommendation.InferenceObservationPlan
import com.debanshu777.caraml.core.recommendation.InstalledModelLoadResolution
import com.debanshu777.caraml.core.recommendation.InstalledModelLoadResolver
import com.debanshu777.caraml.core.recommendation.KvCacheType
import com.debanshu777.caraml.core.recommendation.LlmRunPlan
import com.debanshu777.caraml.core.recommendation.LoadAdmission
import com.debanshu777.caraml.core.recommendation.LoadAdmissionReason
import com.debanshu777.caraml.core.recommendation.LoadRequest
import com.debanshu777.caraml.core.recommendation.ObservationModelIdentity
import com.debanshu777.caraml.core.recommendation.RecommendationProfile
import com.debanshu777.caraml.core.recommendation.task6LlmDescriptor
import com.debanshu777.caraml.core.settings.AppSettings
import com.debanshu777.caraml.core.storage.localmodel.LocalModelDao
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.core.storage.localmodel.LocalModelRepository
import com.debanshu777.caraml.features.chat.domain.ChatConfig
import com.debanshu777.caraml.features.chat.domain.GenerationMode
import com.debanshu777.caraml.features.chat.domain.usecase.GenerateResponseUseCase
import com.debanshu777.caraml.features.chat.domain.usecase.GetAvailableModelsUseCase
import com.debanshu777.caraml.features.chat.domain.usecase.ManageContextUseCase
import com.debanshu777.caraml.features.chat.domain.usecase.TrackModelUsageUseCase
import com.debanshu777.diffusionrunner.DiffusionRunner
import com.debanshu777.runner.InferenceChunk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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

    override suspend fun resolve(
        model: LocalModelEntity,
        expectedMode: GenerationMode,
    ): InstalledModelLoadResolution {
        assertEquals("owner/model", model.modelId)
        assertEquals(GenerationMode.Text, expectedMode)
        calls += 1
        return resolutions.removeFirst()
    }
}

private class RecordingInferenceRepository(
    private val saferRequest: LoadRequest,
) : InferenceRepository {
    val loadedAssessmentKeys = mutableListOf<String>()

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
            else -> error("Unexpected request")
        }
    }

    override suspend fun unloadModel() = Unit
    override fun generateResponse(userPrompt: String): Flow<InferenceChunk> = emptyFlow()
    override fun cancelGeneration() = Unit
    override fun getContextUsed(): Int = 0
    override fun getContextLimit(): Int = 4_096
    override fun getStopReason(): Int = 0
    override fun isContextAboveThreshold(): Boolean = false
    override fun summarizeConversation(transcript: String): Flow<String> = emptyFlow()
    override suspend fun resetContextWithSummary(summary: String, lastExchange: String): Boolean = true
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
    return LoadRequest(
        model = model,
        identity = descriptor.file,
        observationIdentity = requireNotNull(ObservationModelIdentity.fromDescriptor(descriptor)),
        plan = LlmRunPlan(
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
        ),
        assessmentKey = assessmentKey,
    )
}
