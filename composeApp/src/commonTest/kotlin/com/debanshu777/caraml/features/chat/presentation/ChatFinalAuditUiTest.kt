@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.features.chat.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
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
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.ui.layout.AppNavigationLayout
import com.debanshu777.caraml.core.ui.layout.LocalAppNavigationLayout
import com.debanshu777.caraml.core.ui.motion.LocalAuroraMotionPolicy
import com.debanshu777.caraml.core.ui.motion.auroraMotionPolicy
import com.debanshu777.caraml.features.chat.data.ChatMessage
import com.debanshu777.caraml.features.chat.data.LiveGenerationStats
import com.debanshu777.caraml.features.chat.data.MessageRole
import com.debanshu777.caraml.features.chat.domain.GenerationMode
import com.debanshu777.caraml.features.chat.presentation.components.ContextProgressIndicator
import com.debanshu777.caraml.features.chat.presentation.components.GenerationActivity
import com.debanshu777.caraml.features.chat.presentation.components.MessageBubble
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatFinalAuditUiTest {

    @Test
    fun notchedLandscapeConversationConsumesScaffoldStartAndEndInsets() = runComposeUiTest {
        setContent {
            ChatAuditHost {
                ChatScreenContent(
                    uiState = readyState(
                        messages = persistentListOf(
                            ChatMessage(
                                id = "inset-probe",
                                role = MessageRole.Assistant,
                                text = "Inset probe output",
                            ),
                        ),
                    ),
                    streamingState = StreamingState(),
                    onSelectModel = {},
                    onSendMessage = {},
                    onCancelGeneration = {},
                    onNavigateToSearch = {},
                    modifier = Modifier.requiredSize(width = 900.dp, height = 600.dp),
                )
            }
        }

        val scrollOwners = onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes()
        assertEquals(1, scrollOwners.size)
        val bounds = scrollOwners.single().boundsInRoot
        assertTrue(
            bounds.left >= 96f,
            "Conversation must begin after the 72dp start inset and 24dp page gutter: $bounds",
        )
        assertTrue(
            bounds.right <= 820f,
            "Conversation must end before the 56dp end inset and 24dp page gutter: $bounds",
        )
        val composer = onNodeWithTag("create-command").fetchSemanticsNode().boundsInRoot
        assertTrue(
            composer.left >= 96f && composer.right <= 820f,
            "Composer must remain inside the injected horizontal cutouts: $composer",
        )
        val headerStart = onNodeWithText("Create").fetchSemanticsNode().boundsInRoot
        val headerEnd = onNodeWithText("Video").fetchSemanticsNode().boundsInRoot
        assertTrue(
            headerStart.left >= 96f && headerEnd.right <= 820f,
            "Header must remain inside the injected horizontal cutouts: " +
                "start=$headerStart end=$headerEnd",
        )
    }

    @Test
    fun lastMessageClearsMeasuredFourLineComposerAndStatsAtTwoHundredPercent() =
        runComposeUiTest {
            val initialMessages = buildList {
                repeat(12) { index ->
                    add(
                        ChatMessage(
                            id = "message-$index",
                            role = MessageRole.Assistant,
                            text = if (index == 11) "Terminal response" else "Response $index",
                        ),
                    )
                }
            }.toPersistentList()
            var generating by mutableStateOf(false)

            setContent {
                ChatAuditHost {
                    ChatScreenContent(
                        uiState = readyState(
                            messages = initialMessages,
                            isGenerating = generating,
                        ),
                        streamingState = StreamingState(
                            liveStats = LiveGenerationStats(
                                contextUsed = 512,
                                contextLimit = 4_096,
                                outputTokenCount = 24,
                                tokensPerSecond = 12.5,
                            ),
                        ),
                        onSelectModel = {},
                        onSendMessage = {},
                        onCancelGeneration = {},
                        onNavigateToSearch = {},
                        modifier = Modifier.requiredSize(width = 900.dp, height = 600.dp),
                    )
                }
            }

            onNode(hasSetTextAction()).performTextInput("Line one\nLine two\nLine three\nLine four")
            runOnIdle { generating = true }

            val terminal = onNodeWithText("Terminal response").assertIsDisplayed()
                .fetchSemanticsNode().boundsInRoot
            val stats = onNodeWithText("Live output").assertIsDisplayed()
                .fetchSemanticsNode().boundsInRoot
            val statsEnd = onNodeWithText("12.5 tok/s").assertIsDisplayed()
                .fetchSemanticsNode().boundsInRoot
            assertTrue(
                stats.left >= 96f && statsEnd.right <= 820f,
                "Stats must remain inside the injected horizontal cutouts: " +
                    "start=$stats end=$statsEnd",
            )
            assertTrue(
                terminal.bottom <= stats.top,
                "The final message must clear the measured stats/composer region; " +
                    "message=$terminal stats=$stats",
            )
            onAllNodes(hasScrollToIndexAction()).assertCountEquals(1)
        }

    @Test
    fun contextProgressInterpolatesTowardReportedValueForOneHundredEightyMillis() =
        runComposeUiTest {
            var used by mutableStateOf(0)
            mainClock.autoAdvance = false
            setContent {
                MaterialTheme {
                    ContextProgressIndicator(contextUsed = used, contextLimit = 100)
                }
            }
            mainClock.advanceTimeByFrame()

            runOnIdle { used = 100 }
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeBy(90)
            val midway = determinateProgress()
            assertTrue(midway > 0f && midway < 1f, "Progress jumped instead of interpolating: $midway")

            mainClock.advanceTimeBy(100)
            assertEquals(1f, determinateProgress(), absoluteTolerance = 0.001f)
        }

    @Test
    fun generationProgressInterpolatesForOneHundredEightyMillisWithoutASecondAnimation() =
        runComposeUiTest {
            var progress by mutableStateOf(0f)
            mainClock.autoAdvance = false
            setContent {
                MaterialTheme {
                    GenerationActivity(
                        label = "Working locally",
                        progress = progress,
                        modifier = Modifier.testTag("generation-activity"),
                    )
                }
            }
            mainClock.advanceTimeByFrame()
            val signalBefore = signalAverage()

            runOnIdle { progress = 1f }
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeBy(90)
            val midway = determinateProgress()
            val signalMidway = signalAverage()
            assertTrue(midway > 0f && midway < 1f, "Progress jumped instead of interpolating: $midway")
            assertEquals(signalBefore, signalMidway, absoluteTolerance = 0.01f)

            mainClock.advanceTimeBy(100)
            assertEquals(1f, determinateProgress(), absoluteTolerance = 0.001f)
            val settledSignal = signalAverage()
            mainClock.advanceTimeBy(800)
            assertEquals(settledSignal, signalAverage(), absoluteTolerance = 0.01f)
        }

    @Test
    fun reducedContextDisclosureFadesWithoutAnimatingBoundsAndSettlesByNinetyMillis() =
        runComposeUiTest {
            mainClock.autoAdvance = false
            setContent {
                CompositionLocalProvider(
                    LocalAuroraMotionPolicy provides auroraMotionPolicy(durationScale = 0f),
                ) {
                    MaterialTheme {
                        Box(
                            Modifier
                                .requiredSize(width = 320.dp, height = 96.dp)
                                .background(MaterialTheme.colorScheme.surface)
                                .testTag("context-host"),
                        ) {
                            ContextProgressIndicator(
                                contextUsed = 0,
                                contextLimit = 100,
                                modifier = Modifier.testTag("context-disclosure"),
                            )
                        }
                    }
                }
            }
            mainClock.advanceTimeByFrame()

            onNodeWithTag("context-disclosure").performClick()
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeBy(45)
            val midBounds = onNodeWithTag("context-disclosure").fetchSemanticsNode().boundsInRoot
            val midSignal = onNodeWithText("0/100 (0%)")
                .captureToImage().toPixelMap().averageSignal()

            mainClock.advanceTimeBy(50)
            val settledBounds = onNodeWithTag("context-disclosure").fetchSemanticsNode().boundsInRoot
            val settledSignal = onNodeWithText("0/100 (0%)")
                .captureToImage().toPixelMap().averageSignal()
            assertEquals(midBounds.left, settledBounds.left, absoluteTolerance = 0.5f)
            assertEquals(midBounds.top, settledBounds.top, absoluteTolerance = 0.5f)
            assertEquals(midBounds.width, settledBounds.width, absoluteTolerance = 0.5f)
            assertEquals(midBounds.height, settledBounds.height, absoluteTolerance = 0.5f)
            assertTrue(
                abs(midSignal - settledSignal) > 0.001f,
                "Reduced disclosure must visibly fade; mid=$midSignal settled=$settledSignal",
            )

            mainClock.advanceTimeBy(100)
            val afterBudgetSignal = onNodeWithText("0/100 (0%)")
                .captureToImage().toPixelMap().averageSignal()
            assertEquals(
                settledSignal,
                afterBudgetSignal,
                absoluteTolerance = 0.01f,
                message = "Reduced disclosure must settle within the 90ms opacity budget",
            )
        }

    @Test
    fun thoughtsDisclosureHasOneFortyEightDpExpandCollapseActionAtTwoHundredPercent() =
        runComposeUiTest {
            setContent {
                AtTwoHundredPercent {
                    MaterialTheme {
                        MessageBubble(message = thoughtMessage())
                    }
                }
            }

            assertSingleDisclosureAction(state = "Collapsed", actionLabel = "Expand thoughts")
            onNode(hasClickAction(), useUnmergedTree = true).performClick()
            onNodeWithText("Private reasoning").assertIsDisplayed()
            assertSingleDisclosureAction(state = "Expanded", actionLabel = "Collapse thoughts")
        }

    @Test
    fun thoughtsDisclosureExpansionSurvivesSaveableProviderRemoval() = runComposeUiTest {
        var mounted by mutableStateOf(true)
        setContent {
            val stateHolder = rememberSaveableStateHolder()
            AtTwoHundredPercent {
                MaterialTheme {
                    if (mounted) {
                        stateHolder.SaveableStateProvider("thoughts-disclosure") {
                            MessageBubble(message = thoughtMessage())
                        }
                    }
                }
            }
        }

        onNode(hasClickAction(), useUnmergedTree = true).performClick()
        onNodeWithText("Private reasoning").assertIsDisplayed()
        runOnIdle { mounted = false }
        runOnIdle { mounted = true }
        assertSingleDisclosureAction(state = "Expanded", actionLabel = "Collapse thoughts")
        onNodeWithText("Private reasoning").assertIsDisplayed()
    }

    @Test
    fun contextDisclosureHasOneFortyEightDpExpandCollapseActionAtTwoHundredPercent() =
        runComposeUiTest {
            mainClock.autoAdvance = false
            setContent {
                AtTwoHundredPercent {
                    MaterialTheme {
                        ContextProgressIndicator(contextUsed = 25, contextLimit = 100)
                    }
                }
            }
            mainClock.advanceTimeByFrame()

            assertSingleDisclosureAction(
                state = "Collapsed",
                actionLabel = "Expand context usage",
            )
            onNode(hasClickAction(), useUnmergedTree = true).performClick()
            mainClock.advanceTimeByFrame()
            onNodeWithText("25/100 (25%)").assertIsDisplayed()
            assertSingleDisclosureAction(
                state = "Expanded",
                actionLabel = "Collapse context usage",
            )
        }

    @Test
    fun contextDisclosureExpansionSurvivesSaveableProviderRemoval() = runComposeUiTest {
        var mounted by mutableStateOf(true)
        mainClock.autoAdvance = false
        setContent {
            val stateHolder = rememberSaveableStateHolder()
            AtTwoHundredPercent {
                MaterialTheme {
                    if (mounted) {
                        stateHolder.SaveableStateProvider("context-disclosure") {
                            ContextProgressIndicator(contextUsed = 25, contextLimit = 100)
                        }
                    }
                }
            }
        }
        mainClock.advanceTimeByFrame()

        onNode(hasClickAction(), useUnmergedTree = true).performClick()
        mainClock.advanceTimeByFrame()
        onNodeWithText("25/100 (25%)").assertIsDisplayed()
        runOnIdle { mounted = false }
        runOnIdle { mounted = true }
        mainClock.advanceTimeByFrame()
        assertSingleDisclosureAction(
            state = "Expanded",
            actionLabel = "Collapse context usage",
        )
        onNodeWithText("25/100 (25%)").assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.ComposeUiTest.assertSingleDisclosureAction(
        state: String,
        actionLabel: String,
    ) {
        onAllNodes(hasClickAction(), useUnmergedTree = true).assertCountEquals(1)
        val action = onNode(hasClickAction(), useUnmergedTree = true)
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .fetchSemanticsNode()
        assertEquals(state, action.config[SemanticsProperties.StateDescription])
        assertEquals(actionLabel, action.config[SemanticsActions.OnClick].label)
    }

    private fun androidx.compose.ui.test.ComposeUiTest.determinateProgress(): Float {
        val ranges = onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.ProgressBarRangeInfo] }
            .filterNot { it == ProgressBarRangeInfo.Indeterminate }
        assertEquals(1, ranges.size)
        return ranges.single().current
    }

    private fun androidx.compose.ui.test.ComposeUiTest.signalAverage(): Float {
        val pixels = onNodeWithTag("generation-activity-signal", useUnmergedTree = true)
            .captureToImage()
            .toPixelMap()
        var sum = 0f
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                val color = pixels[x, y]
                sum += color.red + color.green + color.blue
            }
        }
        return sum / (pixels.width * pixels.height * 3f)
    }
}

@androidx.compose.runtime.Composable
private fun ChatAuditHost(content: @androidx.compose.runtime.Composable () -> Unit) {
    CompositionLocalProvider(
        LocalDensity provides Density(1f, fontScale = 2f),
        LocalAppNavigationLayout provides AppNavigationLayout.Rail,
        LocalCreateSafeDrawingInsetsOverride provides WindowInsets(
            left = 72.dp,
            top = 18.dp,
            right = 56.dp,
            bottom = 30.dp,
        ),
    ) {
        MaterialTheme(content = content)
    }
}

@androidx.compose.runtime.Composable
private fun AtTwoHundredPercent(content: @androidx.compose.runtime.Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, fontScale = 2f),
        content = content,
    )
}

private fun readyState(
    messages: kotlinx.collections.immutable.ImmutableList<ChatMessage>,
    isGenerating: Boolean = false,
) = ChatUiState.Ready(
    messages = messages,
    generationMode = GenerationMode.Text,
    isGenerating = isGenerating,
)

private fun thoughtMessage() = ChatMessage(
    id = "thought-disclosure",
    role = MessageRole.Assistant,
    text = "Public answer",
    thinking = "Private reasoning",
)

private fun PixelMap.averageSignal(): Float {
    var sum = 0f
    for (y in 0 until height) {
        for (x in 0 until width) {
            val color = this[x, y]
            sum += color.red + color.green + color.blue
        }
    }
    return sum / (width * height * 3f)
}
