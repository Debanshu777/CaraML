@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.features.chat.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.features.chat.data.ChatMessage
import com.debanshu777.caraml.features.chat.data.MessageRole
import com.debanshu777.caraml.features.chat.domain.GenerationMode
import com.debanshu777.caraml.features.chat.presentation.components.ChatInputBar
import com.debanshu777.caraml.features.chat.presentation.components.ChatMessageList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatAuroraUiTest {

    @Test
    fun everyGenerationModeHasSpecificHumanCopy() {
        assertEquals("Think locally. Stay private.", emptyStateCopy(GenerationMode.Text).title)
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
        onNodeWithContentDescription("Send message").performClick()
        runOnIdle {
            assertEquals("Hello", sent)
            generating = true
        }
        onNodeWithContentDescription("Stop generation").performClick()
        runOnIdle { assertEquals(1, cancelled) }
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
}
