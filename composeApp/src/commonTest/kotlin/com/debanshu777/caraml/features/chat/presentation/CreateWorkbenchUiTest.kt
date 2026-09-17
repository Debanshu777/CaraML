@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.features.chat.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.debanshu777.caraml.core.drawer.AppDrawerShell
import com.debanshu777.caraml.core.drawer.DrawerController
import com.debanshu777.caraml.core.drawer.GenerationModeController
import com.debanshu777.caraml.core.drawer.LocalDrawerController
import com.debanshu777.caraml.core.drawer.LocalGenerationModeController
import com.debanshu777.caraml.core.navigation.AppScreen
import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.MemoryTopology
import com.debanshu777.caraml.core.recommendation.KvCacheType
import com.debanshu777.caraml.core.recommendation.LlmRunPlan
import com.debanshu777.caraml.core.recommendation.LoadRequest
import com.debanshu777.caraml.core.recommendation.ObservationModelIdentity
import com.debanshu777.caraml.core.recommendation.RunPlanCompromise
import com.debanshu777.caraml.core.recommendation.task6LlmDescriptor
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.core.ui.layout.AppNavigationLayout
import com.debanshu777.caraml.core.ui.layout.LocalAppNavigationLayout
import com.debanshu777.caraml.core.ui.motion.LocalAuroraMotionPolicy
import com.debanshu777.caraml.core.ui.motion.auroraMotionPolicy
import com.debanshu777.caraml.features.chat.data.ChatMessage
import com.debanshu777.caraml.features.chat.data.InferenceMetrics
import com.debanshu777.caraml.features.chat.data.MessageRole
import com.debanshu777.caraml.features.chat.domain.GenerationMode
import com.debanshu777.caraml.features.chat.presentation.components.MessageBubble
import com.debanshu777.caraml.features.modelhub.presentation.search.ModelHubBrowseMode
import kotlinx.collections.immutable.persistentListOf
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CreateWorkbenchUiTest {

    @Test
    fun createShellSelectionStaysSelectedAcrossTextImageAndVideoModes() = runComposeUiTest {
        lateinit var backStack: NavBackStack<NavKey>
        lateinit var modeController: GenerationModeController

        setContent {
            MaterialTheme {
                backStack = remember { NavBackStack(AppScreen.Home) }
                AppDrawerShell(
                    backStack = backStack,
                    modifier = Modifier.requiredSize(width = 360.dp, height = 800.dp),
                ) {
                    modeController = LocalGenerationModeController.current
                    ReadyCreateScreen(
                        mode = modeController.mode,
                        onModeSelected = modeController::setState,
                    )
                }
            }
        }

        onAllNodesWithContentDescription("Create, selected").assertCountEquals(1)
        onNodeWithContentDescription("Image mode").performClick()
        runOnIdle {
            assertEquals(GenerationMode.Image, modeController.mode)
            assertEquals(AppScreen.Home, backStack.last())
        }
        onAllNodesWithContentDescription("Create, selected").assertCountEquals(1)
        onNodeWithContentDescription("Video mode").performClick()
        runOnIdle {
            assertEquals(GenerationMode.Video, modeController.mode)
            assertEquals(AppScreen.Home, backStack.last())
        }
        onAllNodesWithContentDescription("Create, selected").assertCountEquals(1)
    }

    @Test
    fun modeSwitcherInvokesExactModeWithoutNavigating() = runComposeUiTest {
        val controller = GenerationModeController()
        var modelHubNavigations = 0

        setContent {
            CreateTestLocals(modeController = controller) {
                ChatScreenContent(
                    uiState = readyState(controller.mode),
                    streamingState = StreamingState(),
                    onSelectModel = {},
                    onSendMessage = {},
                    onCancelGeneration = {},
                    onNavigateToSearch = { modelHubNavigations += 1 },
                    modifier = Modifier.requiredSize(width = 360.dp, height = 720.dp),
                    onGenerationModeSelected = controller::setState,
                    controlledGenerationMode = controller.mode,
                )
            }
        }

        onNodeWithContentDescription("Image mode").performClick()
        runOnIdle {
            assertEquals(GenerationMode.Image, controller.mode)
            assertEquals(0, modelHubNavigations)
        }
        onAllNodesWithContentDescription("Image mode, selected").assertCountEquals(1)
    }

    @Test
    fun modeSwitcherRemainsAvailableToRecoverFromModeSpecificError() = runComposeUiTest {
        val controller = GenerationModeController(GenerationMode.Video)

        setContent {
            CreateTestLocals(modeController = controller) {
                ChatScreenContent(
                    uiState = ChatUiState.ModelError("Video generation is not available."),
                    streamingState = StreamingState(),
                    onSelectModel = {},
                    onSendMessage = {},
                    onCancelGeneration = {},
                    onNavigateToSearch = {},
                    modifier = Modifier.requiredSize(width = 360.dp, height = 720.dp),
                    onGenerationModeSelected = controller::setState,
                    controlledGenerationMode = controller.mode,
                )
            }
        }

        onNodeWithContentDescription("Video mode, selected").assertIsDisplayed()
        onNodeWithContentDescription("Text mode").performClick()
        runOnIdle { assertEquals(GenerationMode.Text, controller.mode) }
    }

    @Test
    fun modeSwitcherReadsTheExistingBoundaryWhileReadyStateSettles() = runComposeUiTest {
        val controller = GenerationModeController(GenerationMode.Video)

        setContent {
            CreateTestLocals(modeController = controller) {
                ChatScreenContent(
                    uiState = readyState(GenerationMode.Text),
                    streamingState = StreamingState(),
                    onSelectModel = {},
                    onSendMessage = {},
                    onCancelGeneration = {},
                    onNavigateToSearch = {},
                    modifier = Modifier.requiredSize(width = 360.dp, height = 720.dp),
                    onGenerationModeSelected = controller::setState,
                    controlledGenerationMode = controller.mode,
                )
            }
        }

        onNodeWithContentDescription("Video mode, selected").assertIsDisplayed()
    }

    @Test
    fun emptyCreateHasOneStatementAndOneCommandSurfaceWithoutOuterCard() = runComposeUiTest {
        val backdrop = Color.Magenta
        setContent {
            MaterialTheme {
                Box(
                    Modifier
                        .requiredSize(width = 360.dp, height = 720.dp)
                        .background(backdrop),
                ) {
                    CreateTestLocals {
                        ReadyCreateScreen(
                            mode = GenerationMode.Text,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }

        onAllNodesWithText("Think locally. Stay private.").assertCountEquals(1)
        onAllNodesWithText("Ask anything — your prompt and model stay on this device.")
            .assertCountEquals(1)
        onNodeWithTag("create-command").assertIsDisplayed()

        val emptyState = onNodeWithTag("create-empty-state").captureToImage().toPixelMap()
        assertEquals(
            backdrop,
            emptyState[1, 1],
            "The empty statement must sit directly on the ambient canvas without an outer card",
        )
    }

    @Test
    fun assistantOutputRendersDirectlyOnCanvasWhileUserMessageRemainsTonal() =
        runComposeUiTest {
            val canvas = Color.Magenta
            setContent {
                MaterialTheme {
                    Column(
                        Modifier
                            .requiredSize(width = 320.dp, height = 260.dp)
                            .background(canvas),
                    ) {
                        MessageBubble(
                            message = ChatMessage(
                                id = "assistant",
                                role = MessageRole.Assistant,
                                text = "Direct assistant output",
                            ),
                            modifier = Modifier.testTag("assistant-output"),
                        )
                        MessageBubble(
                            message = ChatMessage(
                                id = "user",
                                role = MessageRole.User,
                                text = "Compact user message",
                            ),
                            modifier = Modifier.testTag("user-output"),
                        )
                    }
                }
            }

            val assistant = onNodeWithTag("assistant-output").captureToImage().toPixelMap()
            val user = onNodeWithTag("user-output").captureToImage().toPixelMap()
            assertEquals(canvas, assistant[1, 1], "Assistant output must not own a bubble")
            assertTrue(
                user[user.width - 20, user.height / 2] != canvas,
                "The user's message must keep one compact tonal surface",
            )
        }

    @Test
    fun stateTraceUsesOnlyPreparingGeneratingAndFinalizingSupportedByStreamingState() =
        runComposeUiTest {
            val message = ChatMessage(
                id = "streaming-media",
                role = MessageRole.Assistant,
                text = "",
            )
            var stream by mutableStateOf(
                StreamingState(
                    streamingMessageId = message.id,
                    pendingMediaGeneration = true,
                    imageGenRequestedSteps = 10,
                ),
            )

            setContent {
                CreateTestLocals {
                    ChatScreenContent(
                        uiState = readyState(
                            mode = GenerationMode.Image,
                            messages = persistentListOf(message),
                            isGenerating = true,
                        ),
                        streamingState = stream,
                        onSelectModel = {},
                        onSendMessage = {},
                        onCancelGeneration = {},
                        onNavigateToSearch = {},
                        modifier = Modifier.requiredSize(width = 360.dp, height = 720.dp),
                    )
                }
            }

            onNode(hasStateDescription("Preparing")).assertIsDisplayed()
            onNodeWithText("Preparing local generation · 10 planned steps · 0s")
                .assertIsDisplayed()
            assertSingleProgress(ProgressBarRangeInfo.Indeterminate)

            runOnIdle {
                stream = stream.copy(imageGenStep = 4, imageGenTotalSteps = 10)
            }
            onNode(hasStateDescription("Generating")).assertIsDisplayed()
            onAllNodes(hasStateDescription("Preparing")).assertCountEquals(0)

            runOnIdle {
                stream = stream.copy(imageGenStep = 10, imageGenTotalSteps = 10)
            }
            onNode(hasStateDescription("Finalizing")).assertIsDisplayed()
            onNodeWithText("Finalizing local output · 0s").assertIsDisplayed()
            onAllNodes(hasStateDescription("Complete")).assertCountEquals(0)
            onAllNodes(hasStateDescription("Error")).assertCountEquals(0)
        }

    @Test
    fun modeChangeUsesBriefLocalMotion() =
        runComposeUiTest {
            var mode by mutableStateOf(GenerationMode.Text)
            mainClock.autoAdvance = false
            setContent {
                CreateTestLocals {
                    ChatScreenContent(
                        uiState = readyState(mode),
                        streamingState = StreamingState(),
                        onSelectModel = {},
                        onSendMessage = {},
                        onCancelGeneration = {},
                        onNavigateToSearch = {},
                        modifier = Modifier.requiredSize(width = 360.dp, height = 720.dp),
                    )
                }
            }
            mainClock.advanceTimeByFrame()

            runOnIdle { mode = GenerationMode.Image }
            mainClock.advanceTimeByFrame()
            onNodeWithText("Think locally. Stay private.").assertExists()
            val entering = onNodeWithText("Create without the cloud.")
                .fetchSemanticsNode().positionInRoot
            mainClock.advanceTimeBy(220)
            val settled = onNodeWithText("Create without the cloud.")
                .fetchSemanticsNode().positionInRoot
            assertTrue(
                abs(entering.y - settled.y) <= 8f,
                "Mode copy may move locally by at most 8dp; entering=$entering settled=$settled",
            )
        }

    @Test
    fun reducedMotionModeChangeUsesNoSpatialOffset() = runComposeUiTest {
            var reducedMode by mutableStateOf(GenerationMode.Text)
            mainClock.autoAdvance = false
            setContent {
                CompositionLocalProvider(
                    LocalAuroraMotionPolicy provides auroraMotionPolicy(durationScale = 0f),
                ) {
                    CreateTestLocals {
                        ChatScreenContent(
                            uiState = readyState(reducedMode),
                            streamingState = StreamingState(),
                            onSelectModel = {},
                            onSendMessage = {},
                            onCancelGeneration = {},
                            onNavigateToSearch = {},
                            modifier = Modifier.requiredSize(width = 360.dp, height = 720.dp),
                        )
                    }
                }
            }
            mainClock.advanceTimeByFrame()
            runOnIdle { reducedMode = GenerationMode.Video }
            mainClock.advanceTimeByFrame()
            val reducedEntering = onNodeWithText("Set ideas in motion.")
                .fetchSemanticsNode().positionInRoot
            mainClock.advanceTimeBy(90)
            val reducedSettled = onNodeWithText("Set ideas in motion.")
                .fetchSemanticsNode().positionInRoot
            assertEquals(reducedEntering.y, reducedSettled.y)
        }

    @Test
    fun shortLandscapeAtTwoHundredPercentKeepsModesComposerAndActionReachable() =
        runComposeUiTest {
            val controller = GenerationModeController()
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f)) {
                    MaterialTheme {
                        CreateTestLocals(modeController = controller) {
                            ReadyCreateScreen(
                                mode = controller.mode,
                                modifier = Modifier.requiredSize(width = 420.dp, height = 280.dp),
                                onModeSelected = controller::setState,
                            )
                        }
                    }
                }
            }

            onNodeWithContentDescription("Text mode, selected").assertIsDisplayed()
            assertTextDoesNotOverflow("Text")
            assertTextDoesNotOverflow("Image")
            assertTextDoesNotOverflow("Video")
            onNodeWithContentDescription("Image mode").performClick()
            runOnIdle { assertEquals(GenerationMode.Image, controller.mode) }
            onNodeWithContentDescription("Image mode, selected").assertIsDisplayed()
            assertTextDoesNotOverflow("Image")
            onNodeWithContentDescription("Video mode").performClick()
            runOnIdle { assertEquals(GenerationMode.Video, controller.mode) }
            onNodeWithContentDescription("Video mode, selected").assertIsDisplayed()
            assertTextDoesNotOverflow("Video")
            onNode(hasSetTextAction()).assertIsDisplayed()
            onNodeWithContentDescription("Send message")
                .assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
        }

    @Test
    fun createChromeRespectsInjectedTopInsetAcrossNavigationLayoutsAtTwoHundredPercent() =
        runComposeUiTest {
            var navigation by mutableStateOf(AppNavigationLayout.BottomBar)
            var width by mutableStateOf(420.dp)
            val safeDrawing = WindowInsets(top = 24.dp, bottom = 32.dp)

            setContent {
                CompositionLocalProvider(
                    LocalDensity provides Density(1f, fontScale = 2f),
                    LocalAppNavigationLayout provides navigation,
                    LocalCreateSafeDrawingInsetsOverride provides safeDrawing,
                ) {
                    MaterialTheme {
                        CreateTestLocals {
                            ReadyCreateScreen(
                                mode = GenerationMode.Text,
                                modifier = Modifier.requiredSize(width = width, height = 360.dp),
                            )
                        }
                    }
                }
            }

            listOf(
                AppNavigationLayout.BottomBar to 420.dp,
                AppNavigationLayout.Rail to 600.dp,
                AppNavigationLayout.Sidebar to 1_200.dp,
            ).forEach { (layout, layoutWidth) ->
                setNavigationLayout(layout, layoutWidth) { nextLayout, nextWidth ->
                    navigation = nextLayout
                    width = nextWidth
                }
                val bounds = if (layout == AppNavigationLayout.Sidebar) {
                    onNodeWithText("Create", useUnmergedTree = true)
                        .fetchSemanticsNode()
                        .boundsInRoot
                } else {
                    onNodeWithContentDescription("Text mode, selected", useUnmergedTree = true)
                        .fetchSemanticsNode()
                        .boundsInRoot
                }
                assertTrue(
                    bounds.top >= 24f,
                    "$layout Create chrome must start below the 24dp safe top; bounds=$bounds",
                )
            }
        }

    @Test
    fun composerOwnsBottomInsetOnlyForRailAndSidebarAtTwoHundredPercent() =
        runComposeUiTest {
            var navigation by mutableStateOf(AppNavigationLayout.BottomBar)
            var width by mutableStateOf(420.dp)
            val hostHeight = 360f
            val safeBottom = 32f
            val safeDrawing = WindowInsets(top = 24.dp, bottom = safeBottom.dp)

            setContent {
                CompositionLocalProvider(
                    LocalDensity provides Density(1f, fontScale = 2f),
                    LocalAppNavigationLayout provides navigation,
                    LocalCreateSafeDrawingInsetsOverride provides safeDrawing,
                ) {
                    MaterialTheme {
                        CreateTestLocals {
                            ReadyCreateScreen(
                                mode = GenerationMode.Text,
                                modifier = Modifier.requiredSize(width = width, height = hostHeight.dp),
                            )
                        }
                    }
                }
            }

            listOf(
                AppNavigationLayout.BottomBar to 420.dp,
                AppNavigationLayout.Rail to 600.dp,
                AppNavigationLayout.Sidebar to 1_200.dp,
            ).forEach { (layout, layoutWidth) ->
                setNavigationLayout(layout, layoutWidth) { nextLayout, nextWidth ->
                    navigation = nextLayout
                    width = nextWidth
                }
                val action = onNodeWithContentDescription("Send message")
                    .assertIsDisplayed()
                    .assertWidthIsAtLeast(48.dp)
                    .assertHeightIsAtLeast(48.dp)
                    .fetchSemanticsNode()
                val bottomGap = hostHeight - action.boundsInRoot.bottom
                if (layout == AppNavigationLayout.BottomBar) {
                    assertTrue(
                        bottomGap < safeBottom,
                        "BottomBar shell already constrains content and must not duplicate its " +
                            "32dp safe bottom; gap=$bottomGap",
                    )
                } else {
                    assertTrue(
                        bottomGap >= safeBottom,
                        "$layout composer must remain above the 32dp safe bottom; gap=$bottomGap",
                    )
                }
            }
        }

    @Test
    fun nonReadyStateMatrixHasOneScrollOwnerAndInvokesExactReachableActions() =
        runComposeUiTest {
            val request = loadRequestForUi()
            val alternativePlan = loadPlanForUi(contextTokens = 1_024)
            var state by mutableStateOf<ChatUiState>(ChatUiState.NoModels)
            var modelHubNavigations = 0
            var detailNavigation: Pair<String, ModelHubBrowseMode>? = null
            var confirmedLoads = 0
            var acceptedAlternatives = 0
            var retriedLoads = 0
            var cancelledLoads = 0

            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f)) {
                    MaterialTheme {
                        CreateTestLocals {
                            ChatScreenContent(
                                uiState = state,
                                streamingState = StreamingState(),
                                onSelectModel = {},
                                onSendMessage = {},
                                onCancelGeneration = {},
                                onConfirmLoad = { confirmedLoads += 1 },
                                onAcceptAlternative = { acceptedAlternatives += 1 },
                                onRetryLoad = { retriedLoads += 1 },
                                onCancelLoad = { cancelledLoads += 1 },
                                onNavigateToSearch = { modelHubNavigations += 1 },
                                onNavigateToModelDetail = { modelId, mode ->
                                    detailNavigation = modelId to mode
                                },
                                modifier = Modifier.requiredSize(width = 420.dp, height = 280.dp),
                            )
                        }
                    }
                }
            }

            fun show(nextState: ChatUiState) {
                runOnIdle { state = nextState }
                waitForIdle()
                onAllNodes(
                    SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy),
                    useUnmergedTree = true,
                ).assertCountEquals(1)
            }

            show(ChatUiState.NoModels)
            onNodeWithText("Download Model").performScrollTo().assertIsDisplayed().performClick()
            runOnIdle { assertEquals(1, modelHubNavigations) }

            show(ChatUiState.NoModelsForMode(GenerationMode.Video))
            onNodeWithText("Browse models").performScrollTo().assertIsDisplayed().performClick()
            runOnIdle { assertEquals(2, modelHubNavigations) }

            show(ChatUiState.ModelLoading)
            onNodeWithText("Loading model...").performScrollTo().assertIsDisplayed()

            show(ChatUiState.ModelError("The selected model could not be opened."))
            onNodeWithText("Try Another Model").performScrollTo().assertIsDisplayed().performClick()
            runOnIdle { assertEquals(3, modelHubNavigations) }

            show(
                ChatUiState.MissingComponents(
                    missingComponentLabels = listOf(
                        "Text encoder",
                        "Variational autoencoder",
                        "Vision projection adapter",
                    ),
                    modelName = "owner/long-diffusion-model",
                    modelId = "owner/long-diffusion-model",
                ),
            )
            onNodeWithText("Download missing components")
                .performScrollTo()
                .assertIsDisplayed()
                .performClick()
            runOnIdle {
                assertEquals(
                    "owner/long-diffusion-model" to ModelHubBrowseMode.DiffusionImage,
                    detailNavigation,
                )
                assertEquals(3, modelHubNavigations)
            }

            show(ChatUiState.LoadActionRequired(PendingLoadAction.ConfirmRisk(request)))
            onNodeWithText("Continue").performScrollTo().assertIsDisplayed().performClick()
            onNodeWithText("Cancel").performScrollTo().assertIsDisplayed().performClick()
            runOnIdle {
                assertEquals(1, confirmedLoads)
                assertEquals(1, cancelledLoads)
            }

            show(
                ChatUiState.LoadActionRequired(
                    PendingLoadAction.AcceptAlternative(request, alternativePlan),
                ),
            )
            onNodeWithText("Use safer plan").performScrollTo().assertIsDisplayed().performClick()
            onNodeWithText("Cancel").performScrollTo().assertIsDisplayed().performClick()
            runOnIdle {
                assertEquals(1, acceptedAlternatives)
                assertEquals(2, cancelledLoads)
            }

            show(ChatUiState.LoadActionRequired(PendingLoadAction.RetryQuarantined(request)))
            onNodeWithText("Retry explicitly").performScrollTo().assertIsDisplayed().performClick()
            onNodeWithText("Cancel").performScrollTo().assertIsDisplayed().performClick()
            runOnIdle {
                assertEquals(1, retriedLoads)
                assertEquals(3, cancelledLoads)
            }
        }

    @Test
    fun lightInferenceStatisticsMeetBodyTextContrast() = assertInferenceStatsContrast(
        scheme = lightColorScheme(
            surface = Color.White,
            onSurface = Color.Black,
            onSurfaceVariant = Color(0xFF49454F),
        ),
    )

    @Test
    fun darkInferenceStatisticsMeetBodyTextContrast() = assertInferenceStatsContrast(
        scheme = darkColorScheme(
            surface = Color.Black,
            onSurface = Color.White,
            onSurfaceVariant = Color(0xFFCAC4D0),
        ),
    )

    private fun assertInferenceStatsContrast(scheme: ColorScheme) = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, fontScale = 1f)) {
                MaterialTheme(colorScheme = scheme) {
                    Surface(
                        modifier = Modifier
                            .requiredSize(width = 420.dp, height = 180.dp)
                            .testTag("inference-stats-root"),
                        color = scheme.surface,
                    ) {
                        MessageBubble(
                            message = ChatMessage(
                                id = "metrics",
                                role = MessageRole.Assistant,
                                text = "Local response",
                                inferenceMetrics = InferenceMetrics(
                                    tpotMs = 400.0,
                                    tokenCount = 42,
                                    generationTimeMs = 1_230L,
                                ),
                            ),
                        )
                    }
                }
            }
        }

        val rootNode = onNodeWithTag("inference-stats-root", useUnmergedTree = true)
            .fetchSemanticsNode()
        val pixels = onNodeWithTag("inference-stats-root", useUnmergedTree = true)
            .captureToImage()
            .toPixelMap()
        listOf("Statistics:", "2.5 tokens/s", "42 tokens", "1.23s").forEach { label ->
            val bounds = onNodeWithText(label, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
                .translate(-rootNode.boundsInRoot.left, -rootNode.boundsInRoot.top)
            val contrast = pixels.maximumContrastAgainst(scheme.surface, bounds)
            assertTrue(
                contrast >= 4.5f,
                "$label must meet 4.5:1 body-text contrast; measured $contrast in $scheme",
            )
        }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.assertSingleProgress(
        expected: ProgressBarRangeInfo,
    ) {
        val progressNodes = onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        assertEquals(1, progressNodes.size)
        assertEquals(expected, progressNodes.single().config[SemanticsProperties.ProgressBarRangeInfo])
    }

    private fun androidx.compose.ui.test.ComposeUiTest.setNavigationLayout(
        layout: AppNavigationLayout,
        width: Dp,
        update: (AppNavigationLayout, Dp) -> Unit,
    ) {
        runOnIdle { update(layout, width) }
        waitForIdle()
    }

    private fun androidx.compose.ui.test.ComposeUiTest.assertTextDoesNotOverflow(text: String) {
        val layoutResults = mutableListOf<TextLayoutResult>()
        onNodeWithText(text, useUnmergedTree = true)
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                assertTrue(action(layoutResults), "Expected a text layout result for $text")
            }
        assertEquals(1, layoutResults.size)
        val result = layoutResults.single()
        assertTrue(
            !result.didOverflowWidth,
            "$text must not overflow horizontally at 200% font scale; " +
                "size=${result.size}, lines=${result.lineCount}",
        )
        assertTrue(
            !result.didOverflowHeight,
            "$text must not overflow vertically at 200% font scale; " +
                "size=${result.size}, lines=${result.lineCount}",
        )
    }
}

private fun loadPlanForUi(contextTokens: Int = 2_048) = LlmRunPlan(
    contextTokens = contextTokens,
    batchSize = 128,
    microBatchSize = 64,
    sequenceCount = 1,
    keyCacheType = KvCacheType.Q8_0,
    valueCacheType = KvCacheType.Q8_0,
    backend = BackendKind.CPU,
    memoryTopology = MemoryTopology.UNKNOWN,
    gpuLayerCount = 0,
    compromises = listOf(RunPlanCompromise.CONTEXT_REDUCED),
)

private fun loadRequestForUi(): LoadRequest {
    val descriptor = task6LlmDescriptor()
    return LoadRequest(
        model = LocalModelEntity(
            id = 1L,
            modelId = "owner/model",
            filename = "model-Q4_K_M.gguf",
            localPath = "/models/model-Q4_K_M.gguf",
            sizeBytes = descriptor.file.sizeBytes,
            downloadedAt = 1L,
            author = "owner",
            libraryName = "gguf",
            pipelineTag = "text-generation",
        ),
        identity = descriptor.file,
        observationIdentity = requireNotNull(ObservationModelIdentity.fromDescriptor(descriptor)),
        plan = loadPlanForUi(),
        assessmentKey = "create-ui-assessment",
    )
}

private fun androidx.compose.ui.graphics.PixelMap.maximumContrastAgainst(
    background: Color,
    bounds: androidx.compose.ui.geometry.Rect,
): Float {
    val left = bounds.left.toInt().coerceIn(0, width - 1)
    val top = bounds.top.toInt().coerceIn(0, height - 1)
    val right = bounds.right.toInt().coerceIn(left + 1, width)
    val bottom = bounds.bottom.toInt().coerceIn(top + 1, height)
    var maximum = 1f
    for (y in top until bottom) {
        for (x in left until right) {
            val foregroundLuminance = this[x, y].luminance()
            val backgroundLuminance = background.luminance()
            val contrast = (maxOf(foregroundLuminance, backgroundLuminance) + 0.05f) /
                (minOf(foregroundLuminance, backgroundLuminance) + 0.05f)
            maximum = maxOf(maximum, contrast)
        }
    }
    return maximum
}

@Composable
private fun ReadyCreateScreen(
    mode: GenerationMode,
    modifier: Modifier = Modifier,
    onModeSelected: (GenerationMode) -> Unit = {},
) {
    ChatScreenContent(
        uiState = readyState(mode),
        streamingState = StreamingState(),
        onSelectModel = {},
        onSendMessage = {},
        onCancelGeneration = {},
        onNavigateToSearch = {},
        modifier = modifier,
        onGenerationModeSelected = onModeSelected,
        controlledGenerationMode = mode,
    )
}

private fun readyState(
    mode: GenerationMode,
    messages: kotlinx.collections.immutable.ImmutableList<ChatMessage> = persistentListOf(),
    isGenerating: Boolean = false,
) = ChatUiState.Ready(
    messages = messages,
    generationMode = mode,
    isGenerating = isGenerating,
)

@Composable
private fun CreateTestLocals(
    modeController: GenerationModeController? = null,
    content: @Composable () -> Unit,
) {
    val resolvedModeController = modeController ?: remember { GenerationModeController() }
    CompositionLocalProvider(
        LocalDrawerController provides remember { DrawerController() },
        LocalGenerationModeController provides resolvedModeController,
        content = content,
    )
}

private fun hasStateDescription(value: String): SemanticsMatcher =
    SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)
