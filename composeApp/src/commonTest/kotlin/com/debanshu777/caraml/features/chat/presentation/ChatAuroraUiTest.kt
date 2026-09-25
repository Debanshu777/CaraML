@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.features.chat.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.features.chat.data.ChatMessage
import com.debanshu777.caraml.features.chat.data.LiveGenerationStats
import com.debanshu777.caraml.features.chat.data.MessageRole
import com.debanshu777.caraml.core.drawer.DrawerController
import com.debanshu777.caraml.core.drawer.LocalDrawerController
import com.debanshu777.caraml.core.ui.motion.LocalAuroraMotionPolicy
import com.debanshu777.caraml.core.ui.motion.auroraMotionPolicy
import com.debanshu777.caraml.features.chat.domain.GenerationMode
import com.debanshu777.caraml.features.chat.presentation.components.ChatInputBar
import com.debanshu777.caraml.features.chat.presentation.components.ChatMessageList
import com.debanshu777.caraml.features.chat.presentation.components.GenerationActivity
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatAuroraUiTest {

    @Test
    fun wideChatAlignsConversationComposerAndGenerationStatsToOneReadableWidth() =
        assertChatSurfaceAlignment(viewportWidth = 1200.dp, expectedBodyWidth = 792f)

    @Test
    fun compactChatKeepsConversationComposerAndGenerationStatsOnTheSameMargins() =
        assertChatSurfaceAlignment(viewportWidth = 360.dp, expectedBodyWidth = 328f)

    @Test
    fun focusedComposerUsesOneSolidSemanticBoundary() =
        runComposeUiTest {
            val host = Color.White
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    MaterialTheme(
                        colorScheme = lightColorScheme(
                            primary = Color.Red,
                            tertiary = Color.Blue,
                            surface = host,
                            surfaceContainer = host,
                            surfaceContainerHigh = host,
                        ),
                    ) {
                        Box(
                            Modifier
                                .requiredSize(width = 360.dp, height = 180.dp)
                                .background(host)
                                .testTag("generating-composer-host"),
                        ) {
                            ChatInputBar(
                                generationMode = GenerationMode.Text,
                                isGenerating = false,
                                selectedModel = null,
                                topModels = persistentListOf(),
                                onSelectModel = {},
                                onDownloadModelClick = {},
                                onSendMessage = {},
                                onCancelGeneration = {},
                            )
                        }
                    }
                }
            }

            val restingPixels = onNodeWithTag("generating-composer-host")
                .captureToImage()
                .toPixelMap()
            assertTrue(
                colorDistance(restingPixels[2, 70], restingPixels[357, 70]) <= 0.01f,
                "The resting composer must remain a neutral command surface",
            )

            onNode(hasSetTextAction()).performClick()

            val image = onNodeWithTag("generating-composer-host").captureToImage()
            val pixels = image.toPixelMap()
            val leftBoundary = pixels[2, 70]
            val rightBoundary = pixels[357, 70]

            assertTrue(
                colorDistance(leftBoundary, rightBoundary) <= 0.01f,
                "The focused composer must use one solid boundary instead of a gradient; " +
                    "left=$leftBoundary right=$rightBoundary",
            )
            assertTrue(
                colorDistance(leftBoundary, host) >= 0.05f,
                "Active composer boundary must remain visibly seed-derived",
            )
        }

    @Test
    fun unknownGenerationProgressKeepsItsContainerStaticWhileIndicatorRemainsIndeterminate() =
        runComposeUiTest {
            mainClock.autoAdvance = false
            setContent {
                GenerationActivityTestHost(progress = null, tag = "unknown-generation")
            }
            mainClock.advanceTimeByFrame()

            val progressInfo = onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().single().config[SemanticsProperties.ProgressBarRangeInfo]
            assertEquals(ProgressBarRangeInfo.Indeterminate, progressInfo)

            val before = generationSignalAverage()
            mainClock.advanceTimeBy(800)
            mainClock.advanceTimeByFrame()
            val after = generationSignalAverage()

            assertTrue(
                abs(before - after) <= 0.01f,
                "Unknown progress must not pulse its container; before=$before after=$after",
            )
        }

    @Test
    fun determinateGenerationDoesNotRunASecondContinuousSignalAnimation() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            GenerationActivityTestHost(progress = 0.4f, tag = "known-generation")
        }
        mainClock.advanceTimeByFrame()

        val before = generationSignalAverage()
        mainClock.advanceTimeBy(800)
        mainClock.advanceTimeByFrame()
        val after = generationSignalAverage()

        assertTrue(
            abs(before - after) <= 0.01f,
            "Determinate progress must not run a second continuous signal animation; " +
                "before=$before after=$after",
        )
    }

    @Test
    fun reducedMotionKeepsDeterminateGenerationContainerStatic() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(
                LocalAuroraMotionPolicy provides auroraMotionPolicy(durationScale = 0f),
            ) {
                GenerationActivityTestHost(progress = 0.4f, tag = "reduced-generation")
            }
        }
        mainClock.advanceTimeByFrame()

        val before = generationSignalAverage()
        mainClock.advanceTimeBy(800)
        mainClock.advanceTimeByFrame()
        val after = generationSignalAverage()

        assertTrue(
            abs(before - after) <= 0.01f,
            "Reduced motion must keep the generation container static; before=$before after=$after",
        )
    }

    @Test
    fun everyGenerationModeHasSpecificHumanCopy() {
        assertEquals("Start with a private thought.", emptyStateCopy(GenerationMode.Text).title)
        assertEquals("Create without the cloud.", emptyStateCopy(GenerationMode.Image).title)
        assertEquals("Set ideas in motion.", emptyStateCopy(GenerationMode.Video).title)
    }

    @Test
    fun composerTransformsSendIntoStopWithoutChangingCallbacks() = runComposeUiTest {
        var sent = ""
        var cancelled = 0
        var generating by mutableStateOf(false)
        setContent {
            MaterialTheme {
                ChatInputBar(
                    generationMode = GenerationMode.Text,
                    isGenerating = generating,
                    selectedModel = null,
                    topModels = persistentListOf(),
                    onSelectModel = {},
                    onDownloadModelClick = {},
                    onSendMessage = { sent = it },
                    onCancelGeneration = { cancelled += 1 },
                )
            }
        }

        onNode(hasSetTextAction()).performTextInput("Hello")
        onNodeWithContentDescription("Send message")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        runOnIdle {
            assertEquals("Hello", sent)
            generating = true
        }
        onNodeWithContentDescription("Stop generation")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        runOnIdle { assertEquals(1, cancelled) }
    }

    @Test
    fun composerTransitionNeverExposesTwoInteractiveActions() = runComposeUiTest {
        var generating by mutableStateOf(false)
        mainClock.autoAdvance = false
        setContent {
            MaterialTheme {
                ChatInputBar(
                    generationMode = GenerationMode.Text,
                    isGenerating = generating,
                    selectedModel = null,
                    topModels = persistentListOf(),
                    onSelectModel = {},
                    onDownloadModelClick = {},
                    onSendMessage = {},
                    onCancelGeneration = {},
                )
            }
        }
        mainClock.advanceTimeByFrame()

        runOnIdle { generating = true }
        mainClock.advanceTimeByFrame()

        onAllNodes(
            hasClickAction() and (
                hasContentDescription("Send message") or
                    hasContentDescription("Stop generation")
                ),
        ).assertCountEquals(1)
        onNodeWithContentDescription("Stop generation").assertExists()
    }

    @Test
    fun composerInputAndSingleActionRemainVisibleAtTwoHundredPercentFontScale() =
        runComposeUiTest {
            var generating by mutableStateOf(false)
            setContent {
                AtTwoHundredPercentFontScale {
                    MaterialTheme {
                        Box(Modifier.width(360.dp).height(320.dp)) {
                            ChatInputBar(
                                generationMode = GenerationMode.Text,
                                isGenerating = generating,
                                selectedModel = null,
                                topModels = persistentListOf(),
                                onSelectModel = {},
                                onDownloadModelClick = {},
                                onSendMessage = {},
                                onCancelGeneration = {},
                            )
                        }
                    }
                }
            }

            onNode(hasSetTextAction()).assertIsDisplayed()
            onNodeWithContentDescription("Send message").assertIsDisplayed()

            runOnIdle { generating = true }

            onNodeWithContentDescription("Stop generation").assertIsDisplayed()
            onAllNodes(
                hasClickAction() and (
                    hasContentDescription("Send message") or
                        hasContentDescription("Stop generation")
                    ),
            ).assertCountEquals(1)
        }

    @Test
    fun insertedMessageDoesNotReplayEntryMotionAfterLazyDisposal() = runComposeUiTest {
        val targetText = "Late inserted message"
        var messages by mutableStateOf(persistentListOf<ChatMessage>())
        mainClock.autoAdvance = false

        setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(width = 320.dp, height = 180.dp)) {
                    ChatMessageList(
                        messages = messages,
                        listState = rememberLazyListState(),
                    )
                }
            }
        }
        waitForIdle()

        runOnIdle {
            messages = buildList {
                add(ChatMessage(id = "inserted", role = MessageRole.User, text = targetText))
                repeat(40) { index ->
                    add(
                        ChatMessage(
                            id = "filler-$index",
                            role = MessageRole.User,
                            text = "Filler message $index",
                        ),
                    )
                }
            }.toPersistentList()
        }

        mainClock.advanceTimeByFrame()
        val firstEntryY = onNodeWithText(targetText).fetchSemanticsNode().positionInRoot.y
        mainClock.advanceTimeBy(100)
        val firstEntryMidY = onNodeWithText(targetText).fetchSemanticsNode().positionInRoot.y
        assertTrue(abs(firstEntryY - firstEntryMidY) > 0.5f, "The inserted message must enter once")

        mainClock.advanceTimeBy(300)
        onNode(hasScrollToIndexAction()).performScrollToIndex(35)
        mainClock.advanceTimeByFrame()
        onNodeWithText(targetText).assertDoesNotExist()
        onNode(hasScrollToIndexAction()).performScrollToIndex(0)

        mainClock.advanceTimeByFrame()
        val returnedY = onNodeWithText(targetText).fetchSemanticsNode().positionInRoot.y
        mainClock.advanceTimeBy(100)
        val returnedAfterAnimationTimeY =
            onNodeWithText(targetText).fetchSemanticsNode().positionInRoot.y

        assertTrue(
            abs(returnedY - returnedAfterAnimationTimeY) < 0.1f,
            "A completed entry must not replay when its lazy item is recreated",
        )
    }

    @Test
    fun removedMessageCompletionDoesNotSuppressARealReinsertion() = runComposeUiTest {
        val targetText = "Removed then reinserted message"
        val target = ChatMessage(id = "reinserted", role = MessageRole.User, text = targetText)
        var messages by mutableStateOf(persistentListOf<ChatMessage>())
        mainClock.autoAdvance = false

        setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(width = 320.dp, height = 180.dp)) {
                    ChatMessageList(
                        messages = messages,
                        listState = rememberLazyListState(),
                    )
                }
            }
        }
        waitForIdle()

        runOnIdle { messages = persistentListOf(target) }
        mainClock.advanceTimeBy(300)
        mainClock.advanceTimeByFrame()

        runOnIdle { messages = persistentListOf() }
        mainClock.advanceTimeByFrame()
        onNodeWithText(targetText).assertDoesNotExist()

        runOnIdle { messages = persistentListOf(target) }
        mainClock.advanceTimeByFrame()
        val reinsertedY = onNodeWithText(targetText).fetchSemanticsNode().positionInRoot.y
        mainClock.advanceTimeBy(100)
        val reinsertedMidY = onNodeWithText(targetText).fetchSemanticsNode().positionInRoot.y

        assertTrue(
            abs(reinsertedY - reinsertedMidY) > 0.5f,
            "Removing a message must prune its completed-entry state before reinsertion",
        )
    }
}

private fun assertChatSurfaceAlignment(
    viewportWidth: androidx.compose.ui.unit.Dp,
    expectedBodyWidth: Float,
) = runComposeUiTest {
    val messageText = "Alignment probe output"
    val message = ChatMessage(
        id = "alignment-probe",
        role = MessageRole.Assistant,
        text = messageText,
    )
    setContent {
        MaterialTheme {
            CompositionLocalProvider(
                LocalDrawerController provides remember { DrawerController() },
            ) {
                Box(Modifier.width(viewportWidth).height(800.dp)) {
                    ChatScreenContent(
                        uiState = ChatUiState.Ready(
                            messages = persistentListOf(message),
                            generationMode = GenerationMode.Text,
                            isGenerating = true,
                        ),
                        streamingState = StreamingState(
                            streamingText = messageText,
                            streamingMessageId = message.id,
                            liveStats = LiveGenerationStats(
                                contextUsed = 10,
                                contextLimit = 100,
                                outputTokenCount = 12,
                                tokensPerSecond = 42.5,
                            ),
                        ),
                        onSelectModel = {},
                        onSendMessage = {},
                        onCancelGeneration = {},
                        onNavigateToSearch = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    val body = onNodeWithText(messageText).fetchSemanticsNode().boundsInRoot
    val composer = onNodeWithText("How can I help you today?").fetchSemanticsNode().boundsInRoot
    val stats = onNodeWithContentDescription(
        "Generation speed 42.5 tokens per second",
    ).fetchSemanticsNode().boundsInRoot

    assertBoundsWidth(expectedBodyWidth, body, "conversation body")
    assertAligned(body.left, composer.left, "composer left")
    assertAligned(body.right, composer.right, "composer right")
    assertAligned(body.right, stats.right, "generation stats right")
}

private fun assertBoundsWidth(expected: Float, bounds: Rect, label: String) {
    assertTrue(
        abs(bounds.width - expected) <= 1f,
        "$label width must be $expected px, but was ${bounds.width}px ($bounds)",
    )
}

private fun assertAligned(expected: Float, actual: Float, label: String) {
    assertTrue(
        abs(expected - actual) <= 4f,
        "$label must align within 4px: expected $expected, actual $actual",
    )
}

private fun colorDistance(first: Color, second: Color): Float =
    abs(first.red - second.red) +
        abs(first.green - second.green) +
        abs(first.blue - second.blue)

@Composable
private fun GenerationActivityTestHost(progress: Float?, tag: String) {
    val host = Color.Black
    MaterialTheme(
        colorScheme = lightColorScheme(
            surface = host,
            surfaceContainer = Color.White,
        ),
    ) {
        Box(
            Modifier
                .requiredSize(width = 320.dp, height = 96.dp)
                .background(host),
        ) {
            GenerationActivity(
                label = "Working locally",
                progress = progress,
                modifier = Modifier
                    .requiredSize(width = 320.dp, height = 96.dp)
                    .testTag(tag),
            )
        }
    }
}

private fun androidx.compose.ui.test.ComposeUiTest.generationSignalAverage(): Float {
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

@Composable
private fun AtTwoHundredPercentFontScale(content: @Composable () -> Unit) {
    val current = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(current.density, fontScale = 2f),
        content = content,
    )
}
