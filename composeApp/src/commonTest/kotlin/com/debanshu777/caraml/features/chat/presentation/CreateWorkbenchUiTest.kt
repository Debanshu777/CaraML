@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.features.chat.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.debanshu777.caraml.core.drawer.AppDrawerShell
import com.debanshu777.caraml.core.drawer.DrawerController
import com.debanshu777.caraml.core.drawer.GenerationModeController
import com.debanshu777.caraml.core.drawer.LocalDrawerController
import com.debanshu777.caraml.core.drawer.LocalGenerationModeController
import com.debanshu777.caraml.core.navigation.AppScreen
import com.debanshu777.caraml.core.ui.motion.LocalAuroraMotionPolicy
import com.debanshu777.caraml.core.ui.motion.auroraMotionPolicy
import com.debanshu777.caraml.features.chat.data.ChatMessage
import com.debanshu777.caraml.features.chat.data.MessageRole
import com.debanshu777.caraml.features.chat.domain.GenerationMode
import com.debanshu777.caraml.features.chat.presentation.components.MessageBubble
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
