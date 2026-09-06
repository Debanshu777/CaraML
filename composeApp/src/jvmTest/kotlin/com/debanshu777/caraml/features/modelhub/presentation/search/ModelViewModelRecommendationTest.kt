package com.debanshu777.caraml.features.modelhub.presentation.search

import com.debanshu777.caraml.core.data.settings.SettingsRepository
import com.debanshu777.caraml.core.platform.DeviceCapabilities
import com.debanshu777.caraml.core.platform.DeviceSnapshot
import com.debanshu777.caraml.core.platform.HardwareProfile
import com.debanshu777.caraml.core.platform.MemoryTopology
import com.debanshu777.caraml.core.platform.PowerPolicyState
import com.debanshu777.caraml.core.platform.ResourcePoolConfidence
import com.debanshu777.caraml.core.platform.ResourceSnapshot
import com.debanshu777.caraml.core.platform.ThermalState
import com.debanshu777.caraml.core.recommendation.AssessedPlans
import com.debanshu777.caraml.core.recommendation.AssessmentConfidence
import com.debanshu777.caraml.core.recommendation.AssessmentReason
import com.debanshu777.caraml.core.recommendation.Compatibility
import com.debanshu777.caraml.core.recommendation.Confidence
import com.debanshu777.caraml.core.recommendation.LlmModelDescriptor
import com.debanshu777.caraml.core.recommendation.ModelAssessment
import com.debanshu777.caraml.core.recommendation.ModelDescriptor
import com.debanshu777.caraml.core.recommendation.ModelFileIdentity
import com.debanshu777.caraml.core.recommendation.PersonalizedRecommendation
import com.debanshu777.caraml.core.recommendation.QuantizationEvidence
import com.debanshu777.caraml.core.recommendation.RecommendationCategory
import com.debanshu777.caraml.core.recommendation.RecommendationProfile
import com.debanshu777.caraml.core.recommendation.RecommendationSortKey
import com.debanshu777.caraml.core.recommendation.WorkloadConfig
import com.debanshu777.caraml.core.settings.AppSettings
import com.debanshu777.caraml.core.storage.component.ComponentRepository
import com.debanshu777.caraml.core.storage.component.DownloadedComponentDao
import com.debanshu777.caraml.core.storage.component.DownloadedComponentEntity
import com.debanshu777.caraml.core.storage.component.ModelComponentLinkEntity
import com.debanshu777.caraml.core.storage.localmodel.LocalModelDao
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.core.storage.localmodel.LocalModelRepository
import com.debanshu777.caraml.features.modelhub.domain.ModelMetadataSource
import com.debanshu777.caraml.features.modelhub.domain.ModelRecommendationService
import com.debanshu777.caraml.features.modelhub.domain.RecommendationSnapshotSource
import com.debanshu777.caraml.features.modelhub.domain.RecommendationVariantEvaluator
import com.debanshu777.caraml.features.modelhub.domain.RepositoryVariant
import com.debanshu777.caraml.features.modelhub.domain.RepositoryVariantSet
import com.debanshu777.huggingfacemanager.HuggingFaceApi
import com.debanshu777.huggingfacemanager.api.RemoteHuggingFaceApiService
import com.debanshu777.huggingfacemanager.download.DownloadManager
import com.debanshu777.huggingfacemanager.download.StoragePathProvider
import com.debanshu777.huggingfacemanager.repository.HuggingFaceRepository
import com.debanshu777.huggingfacemanager.usecase.GetModelConfigUseCase
import com.debanshu777.huggingfacemanager.usecase.GetModelDetailUseCase
import com.debanshu777.huggingfacemanager.usecase.GetModelFileTreeUseCase
import com.debanshu777.huggingfacemanager.usecase.GetRecommendationModelDetailUseCase
import com.debanshu777.huggingfacemanager.usecase.ListModelsUseCase
import com.debanshu777.huggingfacemanager.usecase.ListRecommendationModelsUseCase
import com.debanshu777.huggingfacemanager.usecase.SearchModelsUseCase
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ModelViewModelRecommendationTest {
    @Test
    fun laterSearchIntentWinsWhenEarlierResponseCompletesLast() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val requestDispatcher = dispatcher
        val engine = object : MockEngine(MockEngineConfig().apply {
            reuseHandlers = true
            addHandler { request ->
                val query = request.url.parameters["q"]
                if (query == "first") {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                }
                respondJson(searchResponse(query.orEmpty(), "org/$query"))
            }
        }) {
            override val dispatcher: CoroutineDispatcher = requestDispatcher
        }
        val client = HttpClient(engine)
        try {
            val viewModel = viewModel(client, dispatcher)
            viewModel.updateSearchQuery("first")
            viewModel.performSearch()
            firstStarted.await()

            viewModel.updateSearchQuery("second")
            viewModel.performSearch()
            advanceUntilIdle()
            assertEquals("second", viewModel.searchResponse.value?.q)
            assertEquals(listOf("org/second"), viewModel.recommendedModels.value.map { it.repositoryId })

            releaseFirst.complete(Unit)
            advanceUntilIdle()

            assertEquals("second", viewModel.searchResponse.value?.q)
            assertEquals(listOf("org/second"), viewModel.recommendedModels.value.map { it.repositoryId })
        } finally {
            releaseFirst.complete(Unit)
            client.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun browseModeSwitchRejectsLateLanguageModelListResponse() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val listStarted = CompletableDeferred<Unit>()
        val releaseList = CompletableDeferred<Unit>()
        val requestDispatcher = dispatcher
        val engine = object : MockEngine(MockEngineConfig().apply {
            reuseHandlers = true
            addHandler { request ->
                if (request.url.encodedPath == "/models-json") {
                    listStarted.complete(Unit)
                    releaseList.await()
                    respondJson(listResponse("org/stale-language-model"))
                } else {
                    respondJson(searchResponse("unused", "org/unused"))
                }
            }
        }) {
            override val dispatcher: CoroutineDispatcher = requestDispatcher
        }
        val client = HttpClient(engine)
        try {
            val viewModel = viewModel(client, dispatcher)
            viewModel.loadModels()
            listStarted.await()

            viewModel.setBrowseMode(ModelHubBrowseMode.DiffusionImage)
            val curatedIds = viewModel.listResponse.value?.models.orEmpty().filterNotNull().mapNotNull { it.id }
            assertTrue(curatedIds.isNotEmpty())
            assertFalse("org/stale-language-model" in curatedIds)

            releaseList.complete(Unit)
            runCurrent()

            assertEquals(ModelHubBrowseMode.DiffusionImage, viewModel.browseMode.value)
            assertFalse(
                viewModel.listResponse.value?.models.orEmpty().filterNotNull()
                    .mapNotNull { it.id }
                    .contains("org/stale-language-model"),
            )
        } finally {
            releaseList.complete(Unit)
            client.close()
            Dispatchers.resetMain()
        }
    }

    private fun viewModel(client: HttpClient, dispatcher: CoroutineDispatcher): ModelViewModel {
        val storage = FakeStoragePathProvider()
        return ModelViewModel(
            api = huggingFaceApi(client),
            localModelRepository = LocalModelRepository(FakeLocalModelDao()),
            componentRepository = ComponentRepository(FakeDownloadedComponentDao()),
            downloadManager = DownloadManager(storage),
            storagePathProvider = storage,
            deviceCapabilities = DeviceCapabilities(),
            recommendationService = recommendationService(dispatcher),
            settingsRepository = FakeSettingsRepository(),
        )
    }
}

private fun MockRequestHandleScope.respondJson(value: String) = respond(
    content = value,
    status = HttpStatusCode.OK,
    headers = headersOf(HttpHeaders.ContentType, "application/json"),
)

private fun searchResponse(query: String, repositoryId: String): String =
    """{"models":[{"_id":"$repositoryId","id":"$repositoryId","private":false}],"modelsCount":1,"q":"$query"}"""

private fun listResponse(repositoryId: String): String =
    """{"models":[{"id":"$repositoryId","private":false}],"numItemsPerPage":1,"numTotalItems":1,"pageIndex":0}"""

private fun huggingFaceApi(client: HttpClient): HuggingFaceApi {
    val repository = HuggingFaceRepository(
        RemoteHuggingFaceApiService(client, Json { ignoreUnknownKeys = true }, "https://huggingface.co"),
    )
    return object : HuggingFaceApi {
        override val listModels = ListModelsUseCase(repository)
        override val listRecommendationModels = ListRecommendationModelsUseCase(repository)
        override val searchModels = SearchModelsUseCase(repository)
        override val getModelDetail = GetModelDetailUseCase(repository)
        override val getRecommendationModelDetail = GetRecommendationModelDetailUseCase(repository)
        override val getModelFileTree = GetModelFileTreeUseCase(repository)
        override val getModelConfig = GetModelConfigUseCase(repository)
    }
}

private fun recommendationService(dispatcher: CoroutineDispatcher) = ModelRecommendationService(
    metadataSource = ModelMetadataSource { repositoryId, _ ->
        val identity = ModelFileIdentity(
            repositoryId = repositoryId,
            revision = "a".repeat(40),
            path = "model-Q4_K_M.gguf",
            sizeBytes = 100L,
            gitOid = "oid-$repositoryId",
            lfsOid = null,
            xetHash = null,
            evidence = emptyList(),
        )
        RepositoryVariantSet.Ready(
            listOf(
                RepositoryVariant(
                    descriptor = LlmModelDescriptor(
                        repositoryId = repositoryId,
                        revision = identity.revision,
                        file = identity,
                        architecture = "llama",
                        quantization = QuantizationEvidence.Known("Q4_K_M"),
                        parameterCount = 1_000_000L,
                        contextLimit = 4_096,
                        transformerShape = null,
                        ggufVersion = 3,
                        requiredEngineFeatures = emptySet(),
                        evidence = emptyList(),
                    ),
                    displayName = identity.path,
                ),
            ),
        )
    },
    snapshotSource = object : RecommendationSnapshotSource {
        override suspend fun captureInitial(): DeviceSnapshot = recommendationSnapshot()
        override suspend fun refreshResources(previous: DeviceSnapshot): DeviceSnapshot = previous
    },
    variantEvaluator = object : RecommendationVariantEvaluator {
        override suspend fun assess(
            descriptor: ModelDescriptor,
            snapshot: DeviceSnapshot,
            workload: WorkloadConfig,
        ): ModelAssessment = recommendationAssessment(descriptor.repositoryId)

        override fun rebuild(assessment: ModelAssessment, snapshot: DeviceSnapshot): ModelAssessment = assessment

        override fun personalize(
            descriptor: ModelDescriptor,
            assessment: ModelAssessment,
            snapshot: DeviceSnapshot,
            profile: RecommendationProfile,
        ) = PersonalizedRecommendation(
            assessmentKey = assessment.assessmentKey,
            category = RecommendationCategory.RECOMMENDED,
            selectedPlan = null,
            reasons = listOf(AssessmentReason.METADATA_VALIDATED),
            profile = profile,
        )

        override fun sortKey(
            descriptor: ModelDescriptor,
            assessment: ModelAssessment,
            snapshot: DeviceSnapshot,
            profile: RecommendationProfile,
        ) = RecommendationSortKey.create(
            category = RecommendationCategory.RECOMMENDED,
            confidence = assessment.confidence,
            utility = 1.0,
            worstNormalizedHeadroom = 1.0,
            stableId = descriptor.repositoryId,
        )
    },
    evaluationDispatcher = dispatcher,
    clock = { 1_000L },
)

private fun recommendationAssessment(key: String) = ModelAssessment(
    assessmentKey = key,
    compatibility = Compatibility.Compatible,
    planAssessments = AssessedPlans(emptyList(), assessmentKey = key),
    baseHostBudgetBytes = 1_000_000L,
    baseGpuBudgetBytes = null,
    baseSharedBudgetBytes = null,
    baseStorageBudgetBytes = 1_000_000L,
    confidence = AssessmentConfidence(
        compatibility = Confidence.HIGH,
        memory = Confidence.HIGH,
        storage = Confidence.HIGH,
        performance = Confidence.HIGH,
    ),
    evidence = emptyList(),
)

private fun recommendationSnapshot(): DeviceSnapshot {
    val hardware = HardwareProfile(
        cpuArchitecture = "arm64",
        logicalCoreCount = 8,
        performanceCoreCount = 4,
        instructionSets = emptySet(),
        backends = emptyList(),
        memoryTopology = MemoryTopology.UNKNOWN,
        evidence = emptyList(),
    )
    val resources = ResourceSnapshot(
        additionalAllocatableHostBytes = 2_000_000L,
        additionalAllocatableGpuBytes = null,
        currentProcessBytes = 100_000L,
        freeStorageBytes = 2_000_000L,
        osPressureReserveHostBytes = 0L,
        observedAppFootprintNoiseP95Bytes = 0L,
        platformMinimumReserveHostBytes = 1L,
        lowMemory = false,
        thermalState = ThermalState.NOMINAL,
        powerPolicyState = PowerPolicyState.NORMAL,
        capturedAtEpochMs = 1_000L,
        evidence = emptyList(),
        confidence = ResourcePoolConfidence(host = Confidence.HIGH, storage = Confidence.HIGH),
    )
    return DeviceSnapshot(
        hardwareProfile = hardware,
        resources = resources,
        baseHostBudgetBytes = 1_000_000L,
        baseGpuBudgetBytes = null,
        baseSharedBudgetBytes = null,
        baseStorageBudgetBytes = 1_000_000L,
        isFresh = true,
        evidence = emptyList(),
        budgetConfidence = ResourcePoolConfidence(host = Confidence.HIGH, storage = Confidence.HIGH),
    )
}

private class FakeSettingsRepository : SettingsRepository {
    private val settings = MutableStateFlow(AppSettings())

    override fun getSettings(): Flow<AppSettings> = settings
    override suspend fun updateSettings(settings: AppSettings) {
        this.settings.value = settings
    }

    override suspend fun updateRecommendationProfile(profile: RecommendationProfile) = Unit
    override suspend fun completeModelProfileOnboarding(profile: RecommendationProfile) = Unit
}

private class FakeStoragePathProvider : StoragePathProvider {
    override fun getModelsStorageDirectory(modelId: String): String = "/tmp/caraml-test/$modelId"
    override fun getDatabasePath(): String = "/tmp/caraml-test.db"
    override fun fileExists(path: String): Boolean = false
    override fun getAvailableStorageBytes(): Long = 1_000_000L
    override fun getTotalStorageBytes(): Long = 2_000_000L
    override fun isModelFileReadable(path: String): Boolean = false
    override fun isDirectoryReadable(path: String): Boolean = false
    override fun getFileSize(path: String): Long = 0L
    override fun renameFile(from: String, to: String): Boolean = false
    override fun deleteDownloadedModelContent(modelId: String, localPath: String): Boolean = false
}

private class FakeLocalModelDao : LocalModelDao {
    override suspend fun getFilenamesByModelId(modelId: String): List<String> = emptyList()
    override suspend fun deleteByModelIdAndFilename(modelId: String, filename: String) = Unit
    override suspend fun deleteAllForModelId(modelId: String) = Unit
    override suspend fun insert(entity: LocalModelEntity) = Unit
    override fun getAllDownloadedFiles(): Flow<List<LocalModelEntity>> = flowOf(emptyList())
    override fun getDownloadedFilesByType(modelType: String): Flow<List<LocalModelEntity>> = flowOf(emptyList())
    override suspend fun incrementUsageCount(modelId: String, filename: String) = Unit
    override fun getTotalDownloadedSizeBytes(): Flow<Long> = flowOf(0L)
    override suspend fun updateComponentStatus(modelId: String, status: String) = Unit
    override suspend fun getMainModels(): List<LocalModelEntity> = emptyList()
    override suspend fun updateArch(modelId: String, arch: String) = Unit
    override suspend fun demoteMmprojFilesFromMain() = Unit
}

private class FakeDownloadedComponentDao : DownloadedComponentDao {
    override suspend fun insertComponent(entity: DownloadedComponentEntity): Long = 1L
    override suspend fun insertLink(entity: ModelComponentLinkEntity) = Unit
    override suspend fun getByRepoAndPath(repoId: String, filePath: String): DownloadedComponentEntity? = null
    override suspend fun isComponentDownloaded(repoId: String, filePath: String): Boolean = false
    override fun getAllComponents(): Flow<List<DownloadedComponentEntity>> = flowOf(emptyList())
    override suspend fun getComponentsForModel(modelId: String): List<DownloadedComponentEntity> = emptyList()
    override suspend fun deleteByRepoAndPath(repoId: String, filePath: String) = Unit
}
