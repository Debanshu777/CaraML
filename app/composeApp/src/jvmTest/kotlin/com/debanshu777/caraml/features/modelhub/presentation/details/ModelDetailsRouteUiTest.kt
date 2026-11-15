@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.features.modelhub.presentation.details

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import com.debanshu777.caraml.core.data.settings.SettingsRepository
import com.debanshu777.caraml.core.drawer.AppDrawerShell
import com.debanshu777.caraml.core.navigation.AppScreen
import com.debanshu777.caraml.core.navigation.NavigationTransitionDisplay
import com.debanshu777.caraml.core.ui.motion.LocalAuroraMotionPolicy
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
import com.debanshu777.caraml.features.modelhub.presentation.modelHubTestDownloadCoordinator
import com.debanshu777.caraml.features.modelhub.presentation.search.ModelHubBrowseMode
import com.debanshu777.caraml.features.modelhub.presentation.search.ModelViewModel
import com.debanshu777.caraml.sdk.discovery.huggingface.HuggingFaceApi
import com.debanshu777.caraml.sdk.discovery.huggingface.api.RemoteHuggingFaceApiService
import com.debanshu777.caraml.core.download.transfer.DownloadManager
import com.debanshu777.caraml.core.download.transfer.StoragePathProvider
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
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertSame
import java.util.concurrent.atomic.AtomicInteger
import com.debanshu777.caraml.core.theme.CaraMLTheme
import com.debanshu777.caraml.core.theme.ThemeMode
import com.debanshu777.caraml.core.theme.ThemePreferences
import com.debanshu777.caraml.features.modelhub.presentation.search.saveModelHubEvidence

class ModelDetailsRouteUiTest {

    @Test
    fun recreationAndTabChangesReuseRequestsAndPreparedCard() = runComposeUiTest {
        val environment = DetailsRouteEnvironment("org/retained-model")
        var screenPresent by mutableStateOf(true)
        var windowWidth by mutableStateOf(390.dp)
        var registry = SaveableStateRegistry(restoredValues = null) { true }
        try {
            setContent {
                if (screenPresent) CompositionLocalProvider(
                    LocalDensity provides Density(1f), LocalSaveableStateRegistry provides registry,
                ) {
                    CaraMLTheme(ThemePreferences(themeMode = ThemeMode.LIGHT, reduceMotion = true)) {
                        Box(Modifier.requiredWidth(windowWidth).height(780.dp)) {
                            val backStack = remember { NavBackStack<NavKey>(AppScreen.Details(environment.repositoryId)) }
                            AppDrawerShell(backStack = backStack) {
                                NavigationTransitionDisplay(
                                    modifier = Modifier.fillMaxSize(),
                                    backStack = backStack,
                                    motionPolicy = LocalAuroraMotionPolicy.current,
                                    detailOffsetPx = 0,
                                    entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
                                    entryProvider = { key -> NavEntry(key) {
                                        DetailsScreen(environment.viewModel, environment.repositoryId,
                                            ModelHubBrowseMode.LanguageModels, onBack = {})
                                    } },
                                )
                            }
                        }
                    }
                }
            }
            waitUntil { environment.viewModel.modelCardState.value is ModelCardUiState.Ready }
            onNode(hasText("Model card") and hasAnyAncestor(hasTestTag("detail-tabs"))).performClick()
            // Stay away from the document end: wider text legitimately reduces max scroll.
            onNodeWithTag("detail-scroll").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 700f) }
            val position = onNodeWithTag("detail-scroll").fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange].value()
            val ready = environment.viewModel.modelCardState.value as ModelCardUiState.Ready
            assertEquals(3, environment.requests.get(), "One metadata, file-tree, and README request")
            for (width in listOf(840.dp, 390.dp)) {
                // Skiko's StateRestorationTester has no platformEncodeDecode implementation.
                // Exercise actual disposal and a new registry with its saved values instead.
                val saved = runOnIdle { registry.performSave().also { screenPresent = false } }
                waitForIdle()
                runOnIdle {
                    registry = SaveableStateRegistry(saved) { true }
                    windowWidth = width
                    screenPresent = true
                }
                waitForIdle()
                onNode(hasText("Model card") and hasAnyAncestor(hasTestTag("detail-tabs"))).assertIsSelected()
                onNodeWithTag("page-sticky-tabs").assertIsDisplayed()
                assertEquals(position, onNodeWithTag("detail-scroll").fetchSemanticsNode()
                    .config[SemanticsProperties.VerticalScrollAxisRange].value(), "Recreation at $width restores the reading position")
                assertSame(ready, environment.viewModel.modelCardState.value)
                assertEquals(3, environment.requests.get())
            }
            // Desktop resizing changes the shell without recreating the activity.
            for (width in listOf(840.dp, 720.dp, 390.dp)) {
                runOnIdle { windowWidth = width }
                waitForIdle()
                onNode(hasText("Model card") and hasAnyAncestor(hasTestTag("detail-tabs"))).assertIsSelected()
                assertEquals(position, onNodeWithTag("detail-scroll").fetchSemanticsNode()
                    .config[SemanticsProperties.VerticalScrollAxisRange].value())
                assertEquals(3, environment.requests.get())
            }
            repeat(3) {
                onNodeWithText("Variants").performClick().assertIsSelected()
                onNodeWithTag("model-card-markdown").assertDoesNotExist()
                onNodeWithText("Technical details").performClick().assertIsSelected()
                onNode(hasText("Model card") and hasAnyAncestor(hasTestTag("detail-tabs"))).performClick().assertIsSelected()
                onNodeWithTag("model-card-markdown").assertExists()
                assertSame(ready.rendered, (environment.viewModel.modelCardState.value as ModelCardUiState.Ready).rendered,
                    "Disposing the pager page must not discard the prepared Markdown")
                assertEquals(3, environment.requests.get())
            }
        } finally { environment.close() }
    }

    @Test
    fun overviewRemainsAcrossTabsAndHeaderTitleFollowsActualScrollPosition() = runComposeUiTest {
        val environment = DetailsRouteEnvironment("research-collective/Very-Long-Model-Name-That-Always-Wraps-In-The-Overview-GGUF")
        var fontScale by mutableStateOf(1f)
        try {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                    CaraMLTheme(ThemePreferences(themeMode = ThemeMode.LIGHT, reduceMotion = true)) {
                        Box(Modifier.width(390.dp).height(780.dp).testTag("visual-root")) {
                            DetailsScreen(environment.viewModel, environment.repositoryId,
                                ModelHubBrowseMode.LanguageModels, onBack = {})
                        }
                    }
                }
            }
            waitUntil { environment.viewModel.modelCardState.value is ModelCardUiState.Ready }
            onNodeWithTag("detail-collapsed-title").assertDoesNotExist()
            val description = onNodeWithTag("detail-description").fetchSemanticsNode().boundsInRoot
            val tabs = onNodeWithTag("detail-tabs").fetchSemanticsNode().boundsInRoot
            onNodeWithText("Model card").performClick()
            onNodeWithTag("detail-description").assertIsDisplayed()
            assertEquals(description, onNodeWithTag("detail-description").fetchSemanticsNode().boundsInRoot)
            assertEquals(tabs, onNodeWithTag("detail-tabs").fetchSemanticsNode().boundsInRoot,
                "Switching tabs must keep the complete overview and tab position")
            saveModelHubEvidence("details-overview-retained.png")
            for (scale in listOf(1f, 2f)) {
                runOnIdle { fontScale = scale }
                onNodeWithTag("detail-scroll").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -10000f) }
                onNodeWithTag("detail-collapsed-title").assertDoesNotExist()
                val cardTab = onNode(hasText("Model card") and hasAnyAncestor(hasTestTag("detail-tabs")))
                cardTab.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
                cardTab.assertIsFocused()
                val header = onNodeWithTag("page-sticky-chrome").fetchSemanticsNode().boundsInRoot
                onNodeWithTag("detail-scroll").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 900f) }
                onNodeWithTag("detail-collapsed-title").assertIsDisplayed()
                cardTab.assertIsFocused()
                assertEquals(listOf(environment.repositoryId), onNodeWithTag("detail-collapsed-title")
                    .fetchSemanticsNode().config[SemanticsProperties.ContentDescription])
                assertEquals(header, onNodeWithTag("page-sticky-chrome").fetchSemanticsNode().boundsInRoot,
                    "Revealing the compact title must not resize the header")
                val pinnedTabs = onNodeWithTag("page-sticky-tabs").fetchSemanticsNode().boundsInRoot
                assertTrue(abs(pinnedTabs.top - header.bottom) < 1f, "Tabs pin directly below navigation")
                onAllNodesWithText("Variants").assertCountEquals(1)
                onNodeWithText("Variants").assertIsDisplayed()
                onNodeWithText("Technical details").assertIsDisplayed()
                onNodeWithTag("detail-scroll").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 700f) }
                assertEquals(pinnedTabs, onNodeWithTag("page-sticky-tabs").fetchSemanticsNode().boundsInRoot,
                    "The tab row remains fixed while reading further down the article")
                val readingPosition = onNodeWithTag("detail-scroll").fetchSemanticsNode()
                    .config[SemanticsProperties.VerticalScrollAxisRange].value()
                cardTab.performClick()
                assertEquals(readingPosition, onNodeWithTag("detail-scroll").fetchSemanticsNode()
                    .config[SemanticsProperties.VerticalScrollAxisRange].value(), "Selecting the current tab keeps the reading position")
                onNodeWithTag("detail-pager").performTouchInput {
                    down(Offset(width * .8f, centerY))
                    moveTo(Offset(width * .2f, centerY), delayMillis = 300)
                }
                waitForIdle()
                onNodeWithText("Technical details").assertIsSelected()
                assertEquals(readingPosition, onNodeWithTag("detail-scroll").fetchSemanticsNode()
                    .config[SemanticsProperties.VerticalScrollAxisRange].value(), "An unfinished swipe keeps the reading position")
                onNodeWithTag("detail-pager").performTouchInput {
                    moveTo(Offset(width * .8f, centerY), delayMillis = 400)
                    up()
                }
                cardTab.assertIsSelected()
                assertEquals(readingPosition, onNodeWithTag("detail-scroll").fetchSemanticsNode()
                    .config[SemanticsProperties.VerticalScrollAxisRange].value(), "A reversed swipe keeps the reading position")
                saveModelHubEvidence("details-sticky-tabs-${scale.toInt()}.png")
                onNodeWithText("Variants").performClick().assertIsSelected()
                onNodeWithText("No supported GGUF or ONNX bundles found").assertIsDisplayed()
                assertTrue(onNodeWithTag("detail-pager").fetchSemanticsNode().boundsInRoot.top >= pinnedTabs.bottom - 1f,
                    "A short destination starts below the pinned tabs, rather than at its bottom")
                onNodeWithText("Technical details").performClick().assertIsSelected()
                onNodeWithTag("detail-support").assertIsDisplayed()
                onNode(hasText("Model card") and hasAnyAncestor(hasTestTag("detail-tabs"))).performClick().assertIsSelected()
                assertTrue(onNodeWithTag("detail-pager").fetchSemanticsNode().boundsInRoot.top >= pinnedTabs.bottom - 1f)
                saveModelHubEvidence("details-scrolled-header-${scale.toInt()}.png")
                onNodeWithTag("detail-scroll").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -10000f) }
                onNodeWithTag("detail-collapsed-title").assertDoesNotExist()
                onNodeWithTag("detail-title").assertIsDisplayed()
                cardTab.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
                onNodeWithTag("detail-scroll").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 900f) }
                cardTab.assertIsFocused()
                onNodeWithTag("detail-scroll").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -10000f) }
                cardTab.assertIsFocused()
                onNodeWithTag("page-sticky-tabs").assertDoesNotExist()
                assertEquals(0f, onNodeWithTag("detail-scroll").fetchSemanticsNode()
                    .config[SemanticsProperties.VerticalScrollAxisRange].value(), "Focus restoration must not scroll the page back down")
                onNodeWithText("Technical details").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
                onNodeWithText("Technical details").performKeyInput { keyDown(Key.Enter); keyUp(Key.Enter) }
                    .assertIsSelected()
                cardTab.performClick()
            }
        } finally {
            environment.close()
        }
    }

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
            onAllNodesWithText("$owner/$name").assertCountEquals(1)
            onAllNodesWithText(owner).assertCountEquals(0)
            onNodeWithContentDescription("Navigate back").assertExists()
            onAllNodesWithText("Details").assertCountEquals(0)
        } finally {
            environment.close()
        }
    }

    @Test
    fun shellWiredRouteKeepsFullWidthDividerAndInsetTabsAtBothWidths() = runComposeUiTest {
        val environment = DetailsRouteEnvironment("org/layout-model")
        var windowWidth by mutableStateOf(840.dp)
        try {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    MaterialTheme {
                        val backStack = remember {
                            NavBackStack<NavKey>(AppScreen.Details(environment.repositoryId))
                        }
                        Box(Modifier.requiredWidth(windowWidth).height(720.dp)) {
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
            for (width in listOf(840.dp, 1_144.dp)) {
                runOnIdle { windowWidth = width }
                waitForIdle()
                val overview = onNodeWithTag("detail-overview", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                val back = onNodeWithContentDescription("Navigate back", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                assertTrue(abs(back.left - overview.left) < 1f, "Title and header retain the same gutter")
                val divider = onNodeWithTag("detail-section-divider").fetchSemanticsNode().boundsInRoot
                val tabs = onNodeWithTag("detail-tabs").fetchSemanticsNode().boundsInRoot
                assertTrue(divider.left < overview.left && divider.right > overview.right)
                assertTrue(abs(overview.left - tabs.left) < 1f && abs(overview.right - tabs.right) < 1f, "Rounded tabs align with the content gutters")
                onNodeWithText("Technical details").scrollDetailsIntoView().performClick()
                onNodeWithTag("detail-support").assertExists()
                // The tab selection survives responsive resizing.
            }

        } finally {
            environment.close()
        }
    }
}

private class DetailsRouteEnvironment(
    val repositoryId: String,
) : AutoCloseable {
    private val revision = "a".repeat(40)
    val requests = AtomicInteger()
    private val client = HttpClient(
        MockEngine { request ->
            requests.incrementAndGet()
            val body = if (request.url.encodedPath.endsWith("README.md")) {
                "# Model card\n\nA publisher description retained on every tab, explaining the intended model uses and evaluation limits.\n\n" +
                    (1..25).joinToString("\n\n") { "## Section $it\n\nModel usage and evaluation details for section $it." }
            } else if (request.url.encodedPath.contains("/tree/")) {
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
        downloadCoordinator = modelHubTestDownloadCoordinator(),
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
    override suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        this.settings.value = transform(this.settings.value)
    }
    override suspend fun updateRecommendationProfile(profile: RecommendationProfile) = Unit
    override suspend fun completeModelProfileOnboarding(profile: RecommendationProfile) = Unit
}

private class DetailsLocalModelDao : LocalModelDao {
    override suspend fun insert(entity: LocalModelEntity) = Unit
    override fun getAllDownloadedFiles(): Flow<List<LocalModelEntity>> = flowOf(emptyList())
    override fun getDownloadedFilesByType(modelType: String): Flow<List<LocalModelEntity>> = flowOf(emptyList())
    override suspend fun incrementUsageCount(modelId: String, filename: String) = Unit
    override fun getTotalDownloadedSizeBytes(): Flow<Long> = flowOf(0L)
    override suspend fun updateComponentStatus(modelId: String, status: String) = Unit
    override suspend fun getMainModels(): List<LocalModelEntity> = emptyList()
    override suspend fun demoteMmprojFilesFromMain() = Unit
}

private class DetailsDownloadedComponentDao : DownloadedComponentDao {
    override fun getAllComponents(): Flow<List<DownloadedComponentEntity>> = flowOf(emptyList())
    override suspend fun getComponentsForModel(modelId: String): List<DownloadedComponentEntity> = emptyList()
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
    override fun deleteDownloadedModelContent(modelId: String, localPath: String): Boolean = true
}
