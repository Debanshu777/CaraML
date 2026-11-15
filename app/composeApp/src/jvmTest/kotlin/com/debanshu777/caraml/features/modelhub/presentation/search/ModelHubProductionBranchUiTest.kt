@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.features.modelhub.presentation.search

import com.debanshu777.caraml.core.data.settings.RecentlyViewedModel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
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
import kotlinx.coroutines.CompletableDeferred
import com.debanshu777.caraml.core.theme.AppShapes
import com.debanshu777.caraml.core.theme.CaraMLTheme
import com.debanshu777.caraml.core.theme.ThemePreferences
import com.debanshu777.caraml.core.ui.components.AuroraBackdrop
import com.debanshu777.caraml.core.drawer.LocalNavigationMenuAction
import com.debanshu777.caraml.features.modelhub.presentation.downloaded.DownloadsScreen
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
import com.debanshu777.caraml.features.modelhub.presentation.modelHubTestDownloadCoordinator
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubStateKind
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubStateView
import com.debanshu777.caraml.sdk.discovery.huggingface.HuggingFaceApi
import com.debanshu777.caraml.sdk.discovery.huggingface.api.RemoteHuggingFaceApiService
import com.debanshu777.caraml.core.download.transfer.DownloadManager
import com.debanshu777.caraml.core.download.transfer.StoragePathProvider
import com.debanshu777.caraml.sdk.discovery.huggingface.model.ModelDetailResponse
import com.debanshu777.caraml.sdk.discovery.huggingface.model.ModelSort
import com.debanshu777.caraml.sdk.discovery.huggingface.model.ParameterRange
import com.debanshu777.caraml.sdk.discovery.huggingface.repository.HuggingFaceRepository
import com.debanshu777.caraml.sdk.discovery.huggingface.usecase.GetModelConfigUseCase
import com.debanshu777.caraml.sdk.discovery.huggingface.usecase.GetModelDetailUseCase
import com.debanshu777.caraml.sdk.discovery.huggingface.usecase.GetModelFileTreeUseCase
import com.debanshu777.caraml.sdk.discovery.huggingface.usecase.GetModelPageUseCase
import com.debanshu777.caraml.sdk.discovery.huggingface.usecase.GetRecommendationModelDetailUseCase
import com.debanshu777.caraml.sdk.discovery.huggingface.usecase.ListModelsUseCase
import com.debanshu777.caraml.sdk.discovery.huggingface.usecase.ListRecommendationModelsUseCase
import com.debanshu777.caraml.sdk.discovery.huggingface.usecase.SearchModelsUseCase
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
    fun deviceRefreshResamplesStorageWithoutChangingTheModelQuery() = runComposeUiTest {
        val environment = TestModelHubEnvironment()
        try {
            setContent {
                val info = environment.modelViewModel.storageInfo.collectAsState().value
                Text("${info.availableDeviceBytes}")
            }
            waitUntil(timeoutMillis = 10_000) { environment.modelViewModel.storageInfo.value.hasSampled }
            runOnIdle {
                assertEquals(10_000_000L, environment.modelViewModel.storageInfo.value.availableDeviceBytes)
                environment.storage.availableBytes = 6_000_000L
                environment.modelViewModel.refreshDeviceInfo()
            }
            waitUntil(timeoutMillis = 10_000) { environment.modelViewModel.storageInfo.value.availableDeviceBytes == 6_000_000L }
            runOnIdle { assertTrue(environment.listRequests.isEmpty()) }
        } finally {
            environment.close()
        }
    }


    @Test
    fun firstPageLoadingDoesNotShowZeroCountOrEmptyState() = runComposeUiTest {
        val gate = CompletableDeferred<Unit>()
        val environment = TestModelHubEnvironment(firstPageGate = gate)
        try {
            setSearchContent(environment.modelViewModel)
            waitUntil { environment.modelViewModel.results.value.initialLoading }
            onNodeWithText("Loading models").assertIsDisplayed()
            onNodeWithText("0 models loaded").assertDoesNotExist()
            onNodeWithText("No models are available yet.").assertDoesNotExist()

            gate.complete(Unit)
            waitUntil { environment.modelViewModel.results.value.models.isNotEmpty() }
            onNodeWithText("GGUF · 1 matching model · 1 loaded").assertIsDisplayed()
        } finally {
            gate.complete(Unit)
            environment.close()
        }
    }

    @Test
    fun catalogMatchCountIsDistinctFromDeduplicatedLoadedRows() = runComposeUiTest {
        val environment = TestModelHubEnvironment(duplicateCatalogRow = true)
        try {
            setSearchContent(environment.modelViewModel)
            waitUntil { environment.modelViewModel.results.value.models.size == 1 }
            onNodeWithText("GGUF · 2 matching models · 1 loaded").assertIsDisplayed()
            runOnIdle {
                assertEquals(2, environment.modelViewModel.results.value.totalCount)
                assertEquals(1, environment.modelViewModel.results.value.models.size)
            }
        } finally {
            environment.close()
        }
    }

    @Test
    fun visibleDiscoverLoadsFirstPageOnceWithoutUserAction() = runComposeUiTest {
        val environment = TestModelHubEnvironment()
        try {
            setSearchContent(environment.modelViewModel)
            waitUntil {
                environment.listRequests.size == 1 &&
                    environment.modelViewModel.results.value.models.singleOrNull()?.id == "org/browse-result"
            }
            onNodeWithText("browse-result", substring = true).performScrollTo().assertIsDisplayed()
            onNodeWithText("GGUF · 1 matching model · 1 loaded").assertIsDisplayed()

            runOnIdle { environment.modelViewModel.updateSearchQuery("draft only") }
            runOnIdle { assertEquals(1, environment.listRequests.size) }
        } finally {
            environment.close()
        }
    }

    @Test
    fun textBranchBlankSubmitEscapesResponseBackedSearchAndKeepsCommandFirst() =
        runComposeUiTest {
            val environment = TestModelHubEnvironment()
            try {
                environment.modelViewModel.loadModels()
                waitUntil { environment.modelViewModel.results.value.models.singleOrNull()?.id == "org/browse-result" }
                environment.modelViewModel.updateSearchQuery("server")
                environment.modelViewModel.performSearch()
                waitUntil { environment.modelViewModel.results.value.models.singleOrNull()?.id == "org/search-result" }

                setSearchContent(environment.modelViewModel)

                val command = onNodeWithTag("model-command").fetchSemanticsNode().boundsInRoot
                val results = onNodeWithTag("model-primary-results").fetchSemanticsNode().boundsInRoot
                assertTrue(command.bottom <= results.bottom)
                onNodeWithText("search-result", substring = true).performScrollTo().assertIsDisplayed()

                onNode(hasImeAction(ImeAction.Search)).performTextReplacement("")
                onNode(hasImeAction(ImeAction.Search)).performImeAction()

                waitUntil { environment.modelViewModel.results.value.models.singleOrNull()?.id == "org/browse-result" }
                runOnIdle { assertNull(environment.modelViewModel.results.value.key?.committedSearch) }
                onNodeWithText("browse-result", substring = true).performScrollTo().assertIsDisplayed()
                onNodeWithText("GGUF · 1 matching model · 1 loaded").assertIsDisplayed()
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
                        environment.modelViewModel.results.value.inputError,
                    )
                    assertNull(environment.modelViewModel.results.value.key?.committedSearch)
                }
                onNodeWithText("Please enter a search query").assertIsDisplayed()
                onNodeWithText("No models match “   ”.").assertDoesNotExist()
            } finally {
                environment.close()
            }
        }

    @Test
    fun panelAppliesFormatWithOneReloadAndUpdatesEffectiveCount() = runComposeUiTest {
        val environment = TestModelHubEnvironment()
        try {
            setSearchContent(environment.modelViewModel)
            waitUntil { environment.listRequests.size == 1 }
            onNodeWithTag("model-sort-filter-button").assert(
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "3 filter groups"),
            ).performClick()
            onNodeWithText("ONNX").performScrollTo().performClick()
            runOnIdle { assertEquals(1, environment.listRequests.size) }
            onNodeWithText("View models").performClick()

            waitUntil { environment.listRequests.size == 2 }
            onNodeWithTag("model-sort-filter-button").assert(
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "2 filter groups"),
            )
            runOnIdle { assertEquals("onnx", environment.modelViewModel.languageModelFormat.value) }
        } finally {
            environment.close()
        }
    }

    @Test
    fun recentHistoryStaysHiddenAfterQueryIsCleared() = runComposeUiTest {
        val environment = TestModelHubEnvironment(
            recent = listOf(ModelDetailResponse(id = "org/recent-model").recentlyViewed(ModelHubBrowseMode.LanguageModels)),
        )
        try {
            setSearchContent(environment.modelViewModel)
            onNodeWithText("Recently viewed").assertIsDisplayed()
            onNode(hasImeAction(ImeAction.Search)).performClick()
            onNodeWithText("Recently viewed").assertIsDisplayed()
            onNode(hasImeAction(ImeAction.Search)).performTextReplacement("example")
            onNodeWithText("Recently viewed").assertDoesNotExist()
            onNodeWithContentDescription("Clear model search").performClick()
            onNodeWithText("Recently viewed").assertDoesNotExist()
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
            onNodeWithText("stable-diffusion-v-1-4-original", substring = true)
                .performScrollTo()
                .assertIsDisplayed()

            runOnIdle {
                environment.modelViewModel.setBrowseMode(ModelHubBrowseMode.DiffusionVideo)
            }
            onNodeWithText("2 filters active").assertDoesNotExist()
            onNodeWithText("Reset filters").assertDoesNotExist()
            onNodeWithText("Wan_2.1_ComfyUI_repackaged", substring = true)
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
            val results = onNodeWithTag("model-primary-results").fetchSemanticsNode().boundsInRoot
            assertTrue(command.bottom <= results.bottom)
            onNode(hasImeAction(ImeAction.Search)).performTextReplacement("CompVis")
            onNodeWithText("stable-diffusion-v-1-4-original", substring = true)
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
            val results = onNodeWithTag("model-primary-results").fetchSemanticsNode().boundsInRoot
            assertTrue(command.bottom <= results.bottom)
            onNode(hasImeAction(ImeAction.Search)).performTextReplacement("calcuis")
            onNodeWithText("wan-1.3b-gguf", substring = true).performScrollTo().assertIsDisplayed()
            onNodeWithText("Wan_2.1_ComfyUI_repackaged", substring = true).assertDoesNotExist()
        } finally {
            environment.close()
        }
    }

    @Test
    fun downloadsSearchFiltersPresentedRowsLocally() = runComposeUiTest {
        val environment = TestModelHubEnvironment()
        try {
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
                            DownloadsScreen(
                                modelViewModel = environment.modelViewModel,
                                downloadedModelsViewModel = viewModel,
                                onBack = {},
                                onSelectModelAndGoBack = {},
                                onNavigateToDetails = { _, _ -> },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }

            val command = onNodeWithTag("model-command").fetchSemanticsNode().boundsInRoot
            val results = onNodeWithTag("model-primary-results").fetchSemanticsNode().boundsInRoot
            assertTrue(command.bottom <= results.bottom)
            onNode(hasImeAction(ImeAction.Search)).performTextReplacement("alpha")
            onNodeWithContentDescription("Open model org/alpha-model").performScrollTo().assertIsDisplayed()
            onNodeWithContentDescription("Open model org/beta-model").assertDoesNotExist()
        } finally {
            environment.close()
        }
    }

    @Test
    fun downloadsFilteringKeepsSelectedIdentityVisible() = runComposeUiTest {
        val environment = TestModelHubEnvironment()
        try {
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
                            DownloadsScreen(
                                modelViewModel = environment.modelViewModel,
                                downloadedModelsViewModel = viewModel,
                                onBack = {},
                                onSelectModelAndGoBack = {},
                                onNavigateToDetails = { _, _ -> },
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

            onNodeWithContentDescription("Selected org/alpha-model, tap to deselect").performScrollTo().assertIsDisplayed()
            onNodeWithContentDescription("Not selected org/beta-model, tap to select").performScrollTo().assertIsDisplayed()
            onNodeWithContentDescription("Selected org/alpha-model, tap to deselect")
                .assertIsDisplayed()
            onNodeWithText("Delete (1)").assertIsDisplayed()
            runOnIdle { assertEquals(setOf(alpha.id), viewModel.selectedIds.value) }
        } finally {
            environment.close()
        }
    }

    @Test
    fun downloadsShowsReadinessPerRowWithoutFilterChips() =
        runComposeUiTest {
            val environment = TestModelHubEnvironment()
            try {
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
                        CaraMLTheme(ThemePreferences()) {
                            CompositionLocalProvider(LocalNavigationMenuAction provides {}) {
                                AuroraBackdrop {
                                    Box(Modifier.requiredSize(width = 360.dp, height = 760.dp).testTag("visual-root")) {
                                        DownloadsScreen(
                                            modelViewModel = environment.modelViewModel,
                                            downloadedModelsViewModel = viewModel,
                                            onBack = {},
                                            onSelectModelAndGoBack = {},
                                            onNavigateToDetails = { _, _ -> },
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                onNodeWithContentDescription("Open model org/ready-model").performScrollTo().assertIsDisplayed()
                onNodeWithContentDescription("Open model org/partial-model").performScrollTo().assertIsDisplayed()
                onNode(hasText("All") and isSelectable()).assertDoesNotExist()
                onNode(hasText("Ready") and isSelectable()).assertDoesNotExist()
                onNode(hasText("Needs setup") and isSelectable()).assertDoesNotExist()
                saveModelHubEvidence("library-compact.png")
            } finally {
                environment.close()
            }
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
                            .requiredSize(width = 360.dp, height = 640.dp)
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
                            onNavigateToDetails = { _, _, _ -> },
                            onRecommendationInfoClick = {},
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

private class TestModelHubEnvironment(
    private val firstPageGate: CompletableDeferred<Unit>? = null,
    private val duplicateCatalogRow: Boolean = false,
    private val recent: List<RecentlyViewedModel> = emptyList(),
) : AutoCloseable {
    val listRequests = mutableListOf<RecordedListRequest>()

    private val client = HttpClient(
        MockEngine { request ->
            firstPageGate?.await()
            val body = if (request.url.parameters["search"] == "server") {
                """{"models":[{"id":"org/search-result","private":false}],"numItemsPerPage":30,"numTotalItems":1,"pageIndex":0}"""
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
                val row = """{"id":"$repositoryId","private":false}"""
                val rows = if (duplicateCatalogRow) "$row,$row" else row
                """{"models":[$rows],"numItemsPerPage":30,"numTotalItems":${if (duplicateCatalogRow) 2 else 1},"pageIndex":0}"""
            }
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        },
    )
    private val localModelDao = TestLocalModelDao(emptyList())
    val storage = TestStoragePathProvider()

    val modelViewModel = ModelViewModel(
        api = huggingFaceApi(client),
        localModelRepository = LocalModelRepository(localModelDao),
        componentRepository = ComponentRepository(TestDownloadedComponentDao()),
        downloadManager = DownloadManager(storage),
        storagePathProvider = storage,
        deviceCapabilities = DeviceCapabilities(),
        recommendationService = testRecommendationService(),
        settingsRepository = TestSettingsRepository(recent),
        downloadCoordinator = modelHubTestDownloadCoordinator(),
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
        override val getModelPage = GetModelPageUseCase(repository)
        override val listRecommendationModels = ListRecommendationModelsUseCase(repository)
        override val searchModels = SearchModelsUseCase(repository)
        override val getModelDetail = GetModelDetailUseCase(repository)
        override val getModelCard = com.debanshu777.caraml.sdk.discovery.huggingface.usecase.GetModelCardUseCase(repository)
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

private class TestSettingsRepository(private val recent: List<RecentlyViewedModel>) : SettingsRepository {
    override fun getRecentlyViewedModels(): Flow<List<RecentlyViewedModel>> = flowOf(recent)
    private val settings = MutableStateFlow(AppSettings())

    override fun getSettings(): Flow<AppSettings> = settings
    override suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        this.settings.value = transform(this.settings.value)
    }
    override suspend fun updateRecommendationProfile(profile: RecommendationProfile) = Unit
    override suspend fun completeModelProfileOnboarding(profile: RecommendationProfile) = Unit
}

private class TestLocalModelDao(initial: List<LocalModelEntity>) : LocalModelDao {
    private val models = MutableStateFlow(initial)

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
    override suspend fun demoteMmprojFilesFromMain() = Unit
}

private class TestDownloadedComponentDao : DownloadedComponentDao {
    override fun getAllComponents(): Flow<List<DownloadedComponentEntity>> = flowOf(emptyList())
    override suspend fun getComponentsForModel(modelId: String): List<DownloadedComponentEntity> = emptyList()
}

private class TestStoragePathProvider : StoragePathProvider {
    @Volatile var availableBytes = 10_000_000L
    override fun getModelsStorageDirectory(modelId: String): String = "/tmp/models/$modelId"
    override fun getDatabasePath(): String = "/tmp/models.db"
    override fun fileExists(path: String): Boolean = false
    override fun getAvailableStorageBytes(): Long = availableBytes
    override fun getTotalStorageBytes(): Long = 20_000_000L
    override fun isModelFileReadable(path: String): Boolean = false
    override fun isDirectoryReadable(path: String): Boolean = false
    override fun getFileSize(path: String): Long = 0L
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
