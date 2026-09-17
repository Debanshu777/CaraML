@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.features.modelhub.presentation.details

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.debanshu777.caraml.core.data.settings.SettingsRepository
import com.debanshu777.caraml.core.drawer.AppDrawerShell
import com.debanshu777.caraml.core.navigation.AppScreen
import com.debanshu777.caraml.core.platform.DeviceCapabilities
import com.debanshu777.caraml.core.platform.DeviceSnapshot
import com.debanshu777.caraml.core.platform.HardwareProfile
import com.debanshu777.caraml.core.platform.MemoryTopology
import com.debanshu777.caraml.core.platform.PowerPolicyState
import com.debanshu777.caraml.core.platform.ResourcePoolConfidence
import com.debanshu777.caraml.core.platform.ResourceSnapshot
import com.debanshu777.caraml.core.platform.ThermalState
import com.debanshu777.caraml.core.recommendation.Confidence
import com.debanshu777.caraml.core.recommendation.ModelAssessment
import com.debanshu777.caraml.core.recommendation.ModelDescriptor
import com.debanshu777.caraml.core.recommendation.PersonalizedRecommendation
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
import com.debanshu777.caraml.features.modelhub.domain.RepositoryVariantSet
import com.debanshu777.caraml.features.modelhub.presentation.search.ModelHubBrowseMode
import com.debanshu777.caraml.features.modelhub.presentation.search.ModelViewModel
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
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class ModelDetailsRouteUiTest {

    @Test
    fun productionRouteUsesOneOwnerAndNameHierarchy() = runComposeUiTest {
        val owner = "research-collective"
        val name = "MiniCPM5-1B-Claude-Opus-Fable5-Very-Long-Thinking-GGUF"
        val environment = DetailsRouteEnvironment("$owner/$name")
        try {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    MaterialTheme {
                        Box(Modifier.width(420.dp).height(760.dp)) {
                            DetailsScreen(
                                viewModel = environment.viewModel,
                                modelId = environment.repositoryId,
                                hubBrowseMode = ModelHubBrowseMode.LanguageModels,
                                onBack = {},
                            )
                        }
                    }
                }
            }

            waitUntil {
                environment.viewModel.modelDetail.value != null &&
                    !environment.viewModel.isDetailLoading.value
            }
            onAllNodesWithText(owner).assertCountEquals(1)
            onAllNodesWithText(name).assertCountEquals(1)
            onNodeWithText("$owner/$name").assertDoesNotExist()
            onNodeWithText("Artifact").assertExists()
        } finally {
            environment.close()
        }
    }

    @Test
    fun shellWiredRouteExpandsOnlyWhenContentLeavesAtLeast480DpPrimary() = runComposeUiTest {
        val environment = DetailsRouteEnvironment("org/layout-model")
        var windowWidth by mutableStateOf(840.dp)
        try {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    MaterialTheme {
                        val backStack = remember {
                            NavBackStack<NavKey>(AppScreen.Details(environment.repositoryId))
                        }
                        Box(Modifier.width(windowWidth).height(720.dp)) {
                            AppDrawerShell(
                                modifier = Modifier.fillMaxSize(),
                                backStack = backStack,
                            ) {
                                DetailsScreen(
                                    viewModel = environment.viewModel,
                                    modelId = environment.repositoryId,
                                    hubBrowseMode = ModelHubBrowseMode.DiffusionImage,
                                    onBack = {},
                                )
                            }
                        }
                    }
                }
            }

            waitUntil {
                environment.viewModel.modelDetail.value != null &&
                    !environment.viewModel.isDetailLoading.value
            }
            val compactOverview = onNodeWithTag("detail-overview", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            val compactSupport = onNodeWithTag("detail-support", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            assertTrue(
                abs(compactSupport.left - compactOverview.left) < 1f,
                "Outer 840dp leaves only 712dp after rail and margins, so Details must stay compact",
            )

            runOnIdle { windowWidth = 968.dp }
            waitForIdle()

            val expandedOverview = onNodeWithTag("detail-overview", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            val expandedSupport = onNodeWithTag("detail-support", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            assertTrue(expandedSupport.left >= expandedOverview.right)
            assertTrue(
                expandedOverview.width >= 480f,
                "Expanded primary pane must retain at least 480dp; it was ${expandedOverview.width}",
            )
            assertTrue(
                expandedSupport.width in 320f..360f,
                "Support pane must remain 320–360dp; it was ${expandedSupport.width}",
            )
        } finally {
            environment.close()
        }
    }
}

private class DetailsRouteEnvironment(
    val repositoryId: String,
) : AutoCloseable {
    private val revision = "a".repeat(40)
    private val client = HttpClient(
        MockEngine { request ->
            val body = if (request.url.encodedPath.contains("/tree/")) {
                "[]"
            } else {
                """{"id":"$repositoryId","modelId":"$repositoryId","sha":"$revision","private":false,"author":"${repositoryId.substringBefore('/')}","pipeline_tag":"text-generation","library_name":"gguf"}"""
            }
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        },
    )
    private val storage = DetailsStoragePathProvider()

    val viewModel = ModelViewModel(
        api = detailsHuggingFaceApi(client),
        localModelRepository = LocalModelRepository(DetailsLocalModelDao()),
        componentRepository = ComponentRepository(DetailsDownloadedComponentDao()),
        downloadManager = DownloadManager(storage),
        storagePathProvider = storage,
        deviceCapabilities = DeviceCapabilities(),
        recommendationService = detailsRecommendationService(),
        settingsRepository = DetailsSettingsRepository(),
    )

    override fun close() {
        client.close()
    }
}

private fun detailsHuggingFaceApi(client: HttpClient): HuggingFaceApi {
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

private fun detailsRecommendationService() = ModelRecommendationService(
    metadataSource = ModelMetadataSource { repositoryId, _ ->
        RepositoryVariantSet.NeedsInformation(repositoryId)
    },
    snapshotSource = object : RecommendationSnapshotSource {
        override suspend fun captureInitial(): DeviceSnapshot = detailsSnapshot()
        override suspend fun refreshResources(previous: DeviceSnapshot): DeviceSnapshot = previous
    },
    variantEvaluator = object : RecommendationVariantEvaluator {
        override suspend fun assess(
            descriptor: ModelDescriptor,
            snapshot: DeviceSnapshot,
            workload: WorkloadConfig,
        ): ModelAssessment = error("Needs-information route fixtures must not be assessed")

        override suspend fun reassess(
            descriptor: ModelDescriptor,
            previous: ModelAssessment,
            snapshot: DeviceSnapshot,
            workload: WorkloadConfig,
        ): ModelAssessment = error("Needs-information route fixtures must not be reassessed")

        override fun rebuild(
            assessment: ModelAssessment,
            snapshot: DeviceSnapshot,
        ): ModelAssessment = error("Needs-information route fixtures must not be rebuilt")

        override fun personalize(
            descriptor: ModelDescriptor,
            assessment: ModelAssessment,
            snapshot: DeviceSnapshot,
            profile: RecommendationProfile,
        ): PersonalizedRecommendation = error("Needs-information route fixtures must not be personalized")

        override fun sortKey(
            descriptor: ModelDescriptor,
            assessment: ModelAssessment,
            snapshot: DeviceSnapshot,
            profile: RecommendationProfile,
        ): RecommendationSortKey = error("Needs-information route fixtures must not be sorted")
    },
    evaluationDispatcher = Dispatchers.Unconfined,
    clock = { 1_000L },
)

private fun detailsSnapshot(): DeviceSnapshot = DeviceSnapshot(
    hardwareProfile = HardwareProfile(
        cpuArchitecture = "arm64",
        logicalCoreCount = 8,
        performanceCoreCount = 4,
        instructionSets = emptySet(),
        backends = emptyList(),
        memoryTopology = MemoryTopology.UNKNOWN,
        evidence = emptyList(),
    ),
    resources = ResourceSnapshot(
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
    ),
    baseHostBudgetBytes = 1_000_000L,
    baseGpuBudgetBytes = null,
    baseSharedBudgetBytes = null,
    baseStorageBudgetBytes = 1_000_000L,
    isFresh = true,
    evidence = emptyList(),
    budgetConfidence = ResourcePoolConfidence(host = Confidence.HIGH, storage = Confidence.HIGH),
)

private class DetailsSettingsRepository : SettingsRepository {
    private val settings = MutableStateFlow(AppSettings())

    override fun getSettings(): Flow<AppSettings> = settings
    override suspend fun updateSettings(settings: AppSettings) {
        this.settings.value = settings
    }
    override suspend fun updateRecommendationProfile(profile: RecommendationProfile) = Unit
    override suspend fun completeModelProfileOnboarding(profile: RecommendationProfile) = Unit
}

private class DetailsLocalModelDao : LocalModelDao {
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

private class DetailsDownloadedComponentDao : DownloadedComponentDao {
    override suspend fun insertComponent(entity: DownloadedComponentEntity): Long = 0L
    override suspend fun insertLink(entity: ModelComponentLinkEntity) = Unit
    override suspend fun getByRepoAndPath(repoId: String, filePath: String): DownloadedComponentEntity? = null
    override suspend fun isComponentDownloaded(repoId: String, filePath: String): Boolean = false
    override fun getAllComponents(): Flow<List<DownloadedComponentEntity>> = flowOf(emptyList())
    override suspend fun getComponentsForModel(modelId: String): List<DownloadedComponentEntity> = emptyList()
    override suspend fun deleteByRepoAndPath(repoId: String, filePath: String) = Unit
}

private class DetailsStoragePathProvider : StoragePathProvider {
    override fun getModelsStorageDirectory(modelId: String): String = "/tmp/details-models/$modelId"
    override fun getDatabasePath(): String = "/tmp/details-models.db"
    override fun fileExists(path: String): Boolean = false
    override fun getAvailableStorageBytes(): Long = 10_000_000L
    override fun getTotalStorageBytes(): Long = 20_000_000L
    override fun isModelFileReadable(path: String): Boolean = false
    override fun isDirectoryReadable(path: String): Boolean = false
    override fun getFileSize(path: String): Long = 0L
    override fun renameFile(from: String, to: String): Boolean = false
    override fun deleteDownloadedModelContent(modelId: String, localPath: String): Boolean = true
}
