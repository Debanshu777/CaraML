@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.features.modelhub.presentation.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasImeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.AppShapes
import com.debanshu777.caraml.core.data.settings.SettingsRepository
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
import com.debanshu777.caraml.features.modelhub.presentation.downloaded.DownloadedModelsViewModel
import com.debanshu777.caraml.features.modelhub.presentation.downloaded.ReadinessFilter
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubStateKind
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubStateView
import com.debanshu777.huggingfacemanager.HuggingFaceApi
import com.debanshu777.huggingfacemanager.api.RemoteHuggingFaceApiService
import com.debanshu777.huggingfacemanager.download.DownloadManager
import com.debanshu777.huggingfacemanager.download.StoragePathProvider
import com.debanshu777.huggingfacemanager.model.ModelSort
import com.debanshu777.huggingfacemanager.model.ParameterRange
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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelHubProductionBranchUiTest {

    @Test
    fun textBranchBlankSubmitEscapesResponseBackedSearchAndKeepsCommandFirst() =
        runComposeUiTest {
            val environment = TestModelHubEnvironment()
            try {
                environment.modelViewModel.loadModels()
                waitUntil { environment.modelViewModel.listResponse.value != null }
                environment.modelViewModel.updateSearchQuery("server")
                environment.modelViewModel.performSearch()
                waitUntil { environment.modelViewModel.searchResponse.value != null }

                setSearchContent(environment.modelViewModel)

                val command = onNodeWithTag("model-command").fetchSemanticsNode().boundsInRoot
                val context = onNodeWithTag("model-context").fetchSemanticsNode().boundsInRoot
                assertTrue(command.top < context.top)
                onNodeWithText("search-result").performScrollTo().assertIsDisplayed()

                onNode(hasImeAction(ImeAction.Search)).performTextReplacement("")
                onNode(hasImeAction(ImeAction.Search)).performImeAction()

                runOnIdle { assertNull(environment.modelViewModel.searchResponse.value) }
                onNodeWithText("browse-result").performScrollTo().assertIsDisplayed()
                onNodeWithText("1 model").assertIsDisplayed()
            } finally {
                environment.close()
            }
        }

    @Test
    fun textBranchWhitespaceSubmitUsesExistingValidationInsteadOfBrowseLoad() =
        runComposeUiTest {
            val environment = TestModelHubEnvironment()
            try {
                setSearchContent(environment.modelViewModel)

                onNode(hasImeAction(ImeAction.Search)).performTextReplacement("   ")
                onNode(hasImeAction(ImeAction.Search)).performImeAction()

                runOnIdle {
                    assertEquals(
                        "Please enter a search query",
                        environment.modelViewModel.searchError.value,
                    )
                    assertNull(environment.modelViewModel.searchResponse.value)
                }
                onNodeWithText("Please enter a search query")
                    .performScrollTo()
                    .assertIsDisplayed()
                onNodeWithText("No models match “   ”.").assertDoesNotExist()
            } finally {
                environment.close()
            }
        }

    @Test
    fun filterSheetAppliesExactParamsWithOneReloadAndUpdatedRows() = runComposeUiTest {
        val environment = TestModelHubEnvironment()
        try {
            environment.modelViewModel.loadModels()
            waitUntil {
                environment.listRequests.size == 1 &&
                    environment.modelViewModel.listResponse.value != null
            }
            setSearchContent(environment.modelViewModel)
            onNodeWithText("browse-result").performScrollTo().assertIsDisplayed()

            onNodeWithText("Filters").performScrollTo().performClick()
            onNode(hasText("Likes") and isSelectable()).performClick()
            onAllNodes(hasText("3B") and isSelectable())[0].performScrollTo().performClick()
            onAllNodes(hasText("12B") and isSelectable())[1].performScrollTo().performClick()
            onNodeWithText("Done").performScrollTo().performClick()

            waitUntil {
                environment.listRequests.size == 2 &&
                    environment.modelViewModel.listResponse.value
                        ?.models
                        .orEmpty()
                        .filterNotNull()
                        .singleOrNull()
                        ?.id == "org/filtered-result"
            }
            runOnIdle {
                assertEquals(
                    listOf(
                        RecordedListRequest("trending", "min:0,max:6B"),
                        RecordedListRequest("likes", "min:3B,max:12B"),
                    ),
                    environment.listRequests,
                )
            }
            onNodeWithText("filtered-result").performScrollTo().assertIsDisplayed()
            onNodeWithText("browse-result").assertDoesNotExist()
        } finally {
            environment.close()
        }
    }

    @Test
    fun imageAndVideoDoNotLeakTextFiltersIntoCuratedResults() = runComposeUiTest {
        val environment = TestModelHubEnvironment()
        try {
            environment.modelViewModel.updateParams(
                sort = ModelSort.LIKES,
                minParams = ParameterRange.THREE_B,
            )
            environment.modelViewModel.setBrowseMode(ModelHubBrowseMode.DiffusionImage)
            setSearchContent(environment.modelViewModel)

            onNodeWithText("2 filters active").assertDoesNotExist()
            onNodeWithText("Reset filters").assertDoesNotExist()
            onNodeWithText("stable-diffusion-v-1-4-original")
                .performScrollTo()
                .assertIsDisplayed()

            runOnIdle {
                environment.modelViewModel.setBrowseMode(ModelHubBrowseMode.DiffusionVideo)
            }
            onNodeWithText("2 filters active").assertDoesNotExist()
            onNodeWithText("Reset filters").assertDoesNotExist()
            onNodeWithText("Wan_2.1_ComfyUI_repackaged")
                .performScrollTo()
                .assertIsDisplayed()
        } finally {
            environment.close()
        }
    }

    @Test
    fun imageBranchCommandPrecedesContextAndFiltersCuratedRowsLocally() = runComposeUiTest {
        val environment = TestModelHubEnvironment()
        try {
            environment.modelViewModel.setBrowseMode(ModelHubBrowseMode.DiffusionImage)
            setSearchContent(environment.modelViewModel)

            val command = onNodeWithTag("model-command").fetchSemanticsNode().boundsInRoot
            val context = onNodeWithTag("model-context").fetchSemanticsNode().boundsInRoot
            assertTrue(command.top < context.top)
            onNode(hasImeAction(ImeAction.Search)).performTextReplacement("CompVis")
            onNodeWithText("stable-diffusion-v-1-4-original")
                .performScrollTo()
                .assertIsDisplayed()
            onNodeWithText("stable-diffusion-v-1-5").assertDoesNotExist()
        } finally {
            environment.close()
        }
    }

    @Test
    fun videoBranchCommandPrecedesContextAndFiltersCuratedRowsLocally() = runComposeUiTest {
        val environment = TestModelHubEnvironment()
        try {
            environment.modelViewModel.setBrowseMode(ModelHubBrowseMode.DiffusionVideo)
            setSearchContent(environment.modelViewModel)

            val command = onNodeWithTag("model-command").fetchSemanticsNode().boundsInRoot
            val context = onNodeWithTag("model-context").fetchSemanticsNode().boundsInRoot
            assertTrue(command.top < context.top)
            onNode(hasImeAction(ImeAction.Search)).performTextReplacement("calcuis")
            onNodeWithText("wan-1.3b-gguf").performScrollTo().assertIsDisplayed()
            onNodeWithText("Wan_2.1_ComfyUI_repackaged").assertDoesNotExist()
        } finally {
            environment.close()
        }
    }

    @Test
    fun libraryCommandPrecedesContextAndFiltersPresentedRowsLocally() = runComposeUiTest {
        val dao = TestLocalModelDao(
            listOf(
                localModel(1, "org/alpha-model", "alpha.gguf"),
                localModel(2, "org/beta-model", "beta.gguf"),
            ),
        )
        val storage = TestStoragePathProvider()
        val viewModel = DownloadedModelsViewModel(LocalModelRepository(dao), storage)
        setContent {
            DensityOne {
                MaterialTheme {
                    Box(Modifier.requiredSize(width = 360.dp, height = 760.dp)) {
                        DownloadedTabContent(
                            viewModel = viewModel,
                            storageInfo = testStorageInfo(),
                            onSelectModelAndGoBack = {},
                            onNavigateToDetails = { _, _ -> },
                            snackbarHostState = SnackbarHostState(),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }

        val command = onNodeWithTag("model-command").fetchSemanticsNode().boundsInRoot
        val context = onNodeWithTag("model-context").fetchSemanticsNode().boundsInRoot
        assertTrue(command.top < context.top)
        onNode(hasImeAction(ImeAction.Search)).performTextReplacement("alpha")
        onNodeWithText("org/alpha-model").performScrollTo().assertIsDisplayed()
        onNodeWithText("org/beta-model").assertDoesNotExist()
    }

    @Test
    fun libraryFilteringKeepsSelectedIdentityVisible() = runComposeUiTest {
        val alpha = localModel(1, "org/alpha-model", "alpha.gguf")
        val dao = TestLocalModelDao(
            listOf(
                alpha,
                localModel(2, "org/beta-model", "beta.gguf"),
            ),
        )
        val viewModel = DownloadedModelsViewModel(
            LocalModelRepository(dao),
            TestStoragePathProvider(),
        )
        setContent {
            DensityOne {
                MaterialTheme {
                    Box(Modifier.requiredSize(width = 360.dp, height = 760.dp)) {
                        DownloadedTabContent(
                            viewModel = viewModel,
                            storageInfo = testStorageInfo(),
                            onSelectModelAndGoBack = {},
                            onNavigateToDetails = { _, _ -> },
                            snackbarHostState = SnackbarHostState(),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }

        onNodeWithContentDescription("Open model org/alpha-model")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnLongClick)
        onNodeWithText("Delete (1)").assertIsDisplayed()

        onNode(hasImeAction(ImeAction.Search)).performTextReplacement("beta")

        onNodeWithText("org/alpha-model").performScrollTo().assertIsDisplayed()
        onNodeWithText("org/beta-model").performScrollTo().assertIsDisplayed()
        onNodeWithContentDescription("Selected org/alpha-model, tap to deselect")
            .assertIsDisplayed()
        onNodeWithText("Delete (1)").assertIsDisplayed()
        runOnIdle { assertEquals(setOf(alpha.id), viewModel.selectedIds.value) }
    }

    @Test
    fun libraryReadinessIsOneRadioGroupWithOneActionAndExactFilterCallback() =
        runComposeUiTest {
            val ready = localModel(
                id = 1,
                modelId = "org/ready-model",
                filename = "ready.gguf",
                componentStatus = LocalModelEntity.STATUS_READY,
            )
            val partial = localModel(
                id = 2,
                modelId = "org/partial-model",
                filename = "partial.gguf",
                componentStatus = LocalModelEntity.STATUS_PARTIAL,
            )
            val viewModel = DownloadedModelsViewModel(
                LocalModelRepository(TestLocalModelDao(listOf(ready, partial))),
                TestStoragePathProvider(),
            )
            setContent {
                DensityOne {
                    MaterialTheme(shapes = AppShapes) {
                        Box(Modifier.requiredSize(width = 360.dp, height = 760.dp)) {
                            DownloadedTabContent(
                                viewModel = viewModel,
                                storageInfo = testStorageInfo(),
                                onSelectModelAndGoBack = {},
                                onNavigateToDetails = { _, _ -> },
                                snackbarHostState = SnackbarHostState(),
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }

            onNodeWithText("org/ready-model").performScrollTo().assertIsDisplayed()
            onNodeWithTag("model-toolbar", useUnmergedTree = true)
                .performScrollTo()
                .assert(
                    SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup),
                )
            listOf(
                "All" to true,
                "Ready" to false,
                "Needs setup" to false,
            ).forEach { (label, selected) ->
                val option = onNode(
                    hasText(label) and isSelectable(),
                ).performScrollTo().fetchSemanticsNode()
                assertEquals(Role.RadioButton, option.config[SemanticsProperties.Role])
                assertEquals(selected, option.config[SemanticsProperties.Selected])
                assertEquals(AppShapes.small, option.config[SemanticsProperties.Shape])
                val actionCount = onAllNodes(
                    SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes().count { actionNode ->
                    option.boundsInRoot.contains(actionNode.boundsInRoot.center)
                }
                assertEquals(1, actionCount, "$label must expose exactly one click action")
            }
            onNodeWithTag("Selected library readiness All", useUnmergedTree = true)
                .assertIsDisplayed()

            onNode(hasText("Needs setup") and isSelectable()).performClick()

            waitUntil { viewModel.readinessFilter.value == ReadinessFilter.PARTIAL }
            onNode(hasText("All") and isSelectable()).assertIsNotSelected()
            onNode(hasText("Needs setup") and isSelectable()).assertIsSelected()
            onNodeWithTag("Selected library readiness Needs setup", useUnmergedTree = true)
                .assertIsDisplayed()
            onNodeWithText("org/partial-model").performScrollTo().assertIsDisplayed()
            onNodeWithText("org/ready-model").assertDoesNotExist()
        }

    @Test
    fun emptyAndErrorIconsMeetThreeToOneContrastOnDarkSurface() = runComposeUiTest {
        val surface = Color(0xFF10131A)
        val semantic = Color(0xFFC7CAD4)
        val error = Color(0xFFFFB4AB)
        setContent {
            DensityOne {
                MaterialTheme(
                    colorScheme = darkColorScheme(
                        surface = surface,
                        onSurfaceVariant = semantic,
                        error = error,
                    ),
                ) {
                    Box(
                        Modifier
                            .requiredSize(width = 360.dp, height = 420.dp)
                            .background(surface),
                    ) {
                        androidx.compose.foundation.layout.Column {
                            ModelHubStateView(
                                kind = ModelHubStateKind.Empty,
                                message = "Empty marker",
                            )
                            ModelHubStateView(
                                kind = ModelHubStateKind.Error,
                                message = "Error marker",
                            )
                        }
                    }
                }
            }
        }

        assertIconRegionContrast(surface, "Empty marker", stateIndex = 0)
        assertIconRegionContrast(surface, "Error marker", stateIndex = 1)
    }

    private fun androidx.compose.ui.test.ComposeUiTest.setSearchContent(viewModel: ModelViewModel) {
        setContent {
            DensityOne {
                MaterialTheme {
                    Box(Modifier.requiredSize(width = 360.dp, height = 760.dp)) {
                        SearchTabContent(
                            viewModel = viewModel,
                            storageInfo = testStorageInfo(),
                            onNavigateToDetails = { _, _ -> },
                            onRecommendationInfoClick = {},
                            recommendationProfileState = RecommendationProfileUiState(
                                profile = RecommendationProfile(),
                                isAvailable = false,
                                showDialog = false,
                            ),
                            onOpenProfileEditor = {},
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.assertIconRegionContrast(
        surface: Color,
        message: String,
        stateIndex: Int,
    ) {
        val stateNode = onAllNodesWithTag("model-results")[stateIndex]
        val stateBounds = stateNode.fetchSemanticsNode().boundsInRoot
        val messageBounds = onNodeWithText(message).fetchSemanticsNode().boundsInRoot
        val image = stateNode.captureToImage().toPixelMap()
        val iconBottom = (messageBounds.top - stateBounds.top).toInt().coerceIn(1, image.height)
        var strongest = 1f
        var contrastingPixelCount = 0
        for (y in 0 until iconBottom) {
            for (x in 0 until image.width) {
                val contrast = contrastRatio(surface, image[x, y])
                strongest = max(strongest, contrast)
                if (contrast >= 3f) contrastingPixelCount += 1
            }
        }
        assertTrue(
            contrastingPixelCount >= MIN_CONTRASTING_ICON_PIXELS,
            "$message icon had $contrastingPixelCount contrasting pixels; strongest was $strongest:1",
        )
    }
}

@Composable
private fun DensityOne(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalDensity provides Density(density = 1f, fontScale = 1f),
        content = content,
    )
}

private data class RecordedListRequest(
    val sort: String?,
    val parameterRange: String?,
)

private class TestModelHubEnvironment : AutoCloseable {
    val listRequests = mutableListOf<RecordedListRequest>()

    private val client = HttpClient(
        MockEngine { request ->
            val body = if (request.url.encodedPath.contains("quicksearch")) {
                """{"models":[{"_id":"org/search-result","id":"org/search-result","private":false}],"modelsCount":1,"q":"server"}"""
            } else {
                val recorded = RecordedListRequest(
                    sort = request.url.parameters["sort"],
                    parameterRange = request.url.parameters["num_parameters"],
                )
                listRequests += recorded
                val repositoryId = if (
                    recorded == RecordedListRequest("likes", "min:3B,max:12B")
                ) {
                    "org/filtered-result"
                } else {
                    "org/browse-result"
                }
                """{"models":[{"id":"$repositoryId","private":false}],"numItemsPerPage":1,"numTotalItems":1,"pageIndex":0}"""
            }
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        },
    )
    private val localModelDao = TestLocalModelDao(emptyList())
    private val storage = TestStoragePathProvider()

    val modelViewModel = ModelViewModel(
        api = huggingFaceApi(client),
        localModelRepository = LocalModelRepository(localModelDao),
        componentRepository = ComponentRepository(TestDownloadedComponentDao()),
        downloadManager = DownloadManager(storage),
        storagePathProvider = storage,
        deviceCapabilities = DeviceCapabilities(),
        recommendationService = testRecommendationService(),
        settingsRepository = TestSettingsRepository(),
    )

    override fun close() {
        client.close()
    }
}

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

private fun testRecommendationService() = ModelRecommendationService(
    metadataSource = ModelMetadataSource { repositoryId, _ ->
        RepositoryVariantSet.NeedsInformation(repositoryId)
    },
    snapshotSource = object : RecommendationSnapshotSource {
        override suspend fun captureInitial(): DeviceSnapshot = testSnapshot()
        override suspend fun refreshResources(previous: DeviceSnapshot): DeviceSnapshot = previous
    },
    variantEvaluator = object : RecommendationVariantEvaluator {
        override suspend fun assess(
            descriptor: ModelDescriptor,
            snapshot: DeviceSnapshot,
            workload: WorkloadConfig,
        ): ModelAssessment = error("Needs-information fixtures must not be assessed")

        override suspend fun reassess(
            descriptor: ModelDescriptor,
            previous: ModelAssessment,
            snapshot: DeviceSnapshot,
            workload: WorkloadConfig,
        ): ModelAssessment = error("Needs-information fixtures must not be reassessed")

        override fun rebuild(
            assessment: ModelAssessment,
            snapshot: DeviceSnapshot,
        ): ModelAssessment = error("Needs-information fixtures must not be rebuilt")

        override fun personalize(
            descriptor: ModelDescriptor,
            assessment: ModelAssessment,
            snapshot: DeviceSnapshot,
            profile: RecommendationProfile,
        ): PersonalizedRecommendation = error("Needs-information fixtures must not be personalized")

        override fun sortKey(
            descriptor: ModelDescriptor,
            assessment: ModelAssessment,
            snapshot: DeviceSnapshot,
            profile: RecommendationProfile,
        ): RecommendationSortKey = error("Needs-information fixtures must not be sorted")
    },
    evaluationDispatcher = Dispatchers.Unconfined,
    clock = { 1_000L },
)

private fun testSnapshot(): DeviceSnapshot = DeviceSnapshot(
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

private class TestSettingsRepository : SettingsRepository {
    private val settings = MutableStateFlow(AppSettings())

    override fun getSettings(): Flow<AppSettings> = settings
    override suspend fun updateSettings(settings: AppSettings) {
        this.settings.value = settings
    }
    override suspend fun updateRecommendationProfile(profile: RecommendationProfile) = Unit
    override suspend fun completeModelProfileOnboarding(profile: RecommendationProfile) = Unit
}

private class TestLocalModelDao(initial: List<LocalModelEntity>) : LocalModelDao {
    private val models = MutableStateFlow(initial)

    override suspend fun getFilenamesByModelId(modelId: String): List<String> =
        models.value.filter { it.modelId == modelId }.map { it.filename }
    override suspend fun deleteByModelIdAndFilename(modelId: String, filename: String) {
        models.value = models.value.filterNot { it.modelId == modelId && it.filename == filename }
    }
    override suspend fun deleteAllForModelId(modelId: String) {
        models.value = models.value.filterNot { it.modelId == modelId }
    }
    override suspend fun insert(entity: LocalModelEntity) {
        models.value = models.value.filterNot { it.id == entity.id } + entity
    }
    override fun getAllDownloadedFiles(): Flow<List<LocalModelEntity>> = models
    override fun getDownloadedFilesByType(modelType: String): Flow<List<LocalModelEntity>> =
        flowOf(models.value.filter { it.modelType == modelType })
    override suspend fun incrementUsageCount(modelId: String, filename: String) = Unit
    override fun getTotalDownloadedSizeBytes(): Flow<Long> =
        flowOf(models.value.sumOf { it.sizeBytes ?: 0L })
    override suspend fun updateComponentStatus(modelId: String, status: String) = Unit
    override suspend fun getMainModels(): List<LocalModelEntity> = emptyList()
    override suspend fun updateArch(modelId: String, arch: String) = Unit
    override suspend fun demoteMmprojFilesFromMain() = Unit
}

private class TestDownloadedComponentDao : DownloadedComponentDao {
    override suspend fun insertComponent(entity: DownloadedComponentEntity): Long = 0L
    override suspend fun insertLink(entity: ModelComponentLinkEntity) = Unit
    override suspend fun getByRepoAndPath(repoId: String, filePath: String): DownloadedComponentEntity? = null
    override suspend fun isComponentDownloaded(repoId: String, filePath: String): Boolean = false
    override fun getAllComponents(): Flow<List<DownloadedComponentEntity>> = flowOf(emptyList())
    override suspend fun getComponentsForModel(modelId: String): List<DownloadedComponentEntity> = emptyList()
    override suspend fun deleteByRepoAndPath(repoId: String, filePath: String) = Unit
}

private class TestStoragePathProvider : StoragePathProvider {
    override fun getModelsStorageDirectory(modelId: String): String = "/tmp/models/$modelId"
    override fun getDatabasePath(): String = "/tmp/models.db"
    override fun fileExists(path: String): Boolean = false
    override fun getAvailableStorageBytes(): Long = 10_000_000L
    override fun getTotalStorageBytes(): Long = 20_000_000L
    override fun isModelFileReadable(path: String): Boolean = false
    override fun isDirectoryReadable(path: String): Boolean = false
    override fun getFileSize(path: String): Long = 0L
    override fun renameFile(from: String, to: String): Boolean = false
    override fun deleteDownloadedModelContent(modelId: String, localPath: String): Boolean = true
}

private fun localModel(
    id: Long,
    modelId: String,
    filename: String,
    componentStatus: String? = null,
) = LocalModelEntity(
    id = id,
    modelId = modelId,
    filename = filename,
    localPath = "/tmp/models/$modelId/$filename",
    sizeBytes = 1_024L,
    downloadedAt = 0L,
    author = modelId.substringBefore('/'),
    libraryName = "llama.cpp",
    pipelineTag = "text-generation",
    componentStatus = componentStatus,
)

private fun testStorageInfo() = StorageInfoUiState(
    totalDeviceBytes = 20_000_000L,
    availableDeviceBytes = 10_000_000L,
    usedByModelsBytes = 2_048L,
)

private fun contrastRatio(first: Color, second: Color): Float {
    val light = max(relativeLuminance(first), relativeLuminance(second))
    val dark = min(relativeLuminance(first), relativeLuminance(second))
    return (light + 0.05f) / (dark + 0.05f)
}

private fun relativeLuminance(color: Color): Float {
    fun channel(value: Float): Float = if (value <= 0.04045f) {
        value / 12.92f
    } else {
        ((value + 0.055f) / 1.055f).coerceAtLeast(0f).pow(2.4f)
    }
    return 0.2126f * channel(color.red) +
        0.7152f * channel(color.green) +
        0.0722f * channel(color.blue)
}

private const val MIN_CONTRASTING_ICON_PIXELS = 24
