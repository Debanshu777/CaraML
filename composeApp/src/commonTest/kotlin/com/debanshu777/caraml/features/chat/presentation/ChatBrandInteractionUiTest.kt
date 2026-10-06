@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.features.chat.presentation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.drawer.LocalNavigationMenuAction
import com.debanshu777.caraml.features.chat.data.ChatMessage
import com.debanshu777.caraml.features.chat.data.MessageDelivery
import com.debanshu777.caraml.features.chat.data.MessageRole
import com.debanshu777.caraml.features.chat.domain.GenerationMode
import com.debanshu777.caraml.features.chat.presentation.components.MessageBubble
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatBrandInteractionUiTest {
    @Test
    fun mediaIdentityTracksReportedSamplingAndWaitsForTerminalCompletion() = runComposeUiTest {
        var step by mutableStateOf(0)
        var total by mutableStateOf(0)
        var pending by mutableStateOf(true)
        setContent {
            MaterialTheme {
                MessageBubble(
                    message = ChatMessage(
                        id = "media-phase", role = MessageRole.Assistant, text = "",
                        delivery = if (pending) null else MessageDelivery.Complete,
                    ),
                    isStreaming = pending,
                    showMediaPending = pending,
                    imageGenStep = step,
                    imageGenTotalSteps = total,
                    imageGenRequestedSteps = 30,
                )
            }
        }
        onNodeWithText("Getting ready").assertIsDisplayed()
        runOnIdle { step = 12; total = 30 }
        onAllNodesWithText("Getting ready").assertCountEquals(0)
        onAllNodesWithText("Generating").assertCountEquals(1)
        onNodeWithText("Step 12 / 30 · 0s").assertIsDisplayed()
        // A reported step without a known total is still real sampler activity.
        runOnIdle { total = 0 }
        onAllNodesWithText("Generating").assertCountEquals(1)
        onNodeWithText("Step 12 · 0s").assertIsDisplayed()
        runOnIdle { step = 30; total = 30 }
        onAllNodesWithText("Finalizing").assertCountEquals(1)
        onAllNodesWithText("Done").assertCountEquals(0)
        runOnIdle { pending = false }
        onNodeWithText("Done").assertIsDisplayed()
    }

    @Test
    fun starterIdeaEditsDraftAndModeChangeKeepsItUntilExplicitSend() = runComposeUiTest {
        var mode by mutableStateOf(GenerationMode.Text)
        val sent = mutableListOf<String>()
        setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f),
                LocalCreateSafeDrawingInsetsOverride provides WindowInsets(0),
                LocalNavigationMenuAction provides {},
            ) {
                MaterialTheme {
                    ChatScreenContent(
                        uiState = ChatUiState.Ready(generationMode = mode),
                        streamingState = StreamingState(),
                        onSelectModel = {}, onSendMessage = sent::add, onCancelGeneration = {}, onNavigateToSearch = {},
                        onGenerationModeSelected = { mode = it },
                        modifier = Modifier.requiredSize(390.dp, 800.dp),
                    )
                }
            }
        }
        val prompt = "Write a story about a tiny astronaut who opens a noodle shop."
        onNodeWithText("Space noodles").performScrollTo().performClick()
        onNode(hasSetTextAction()).assertTextContains(prompt)
        runOnIdle { assertTrue(sent.isEmpty(), "Starter ideas must only populate a draft") }
        onNode(hasScrollToIndexAction()).performScrollToIndex(1)
        onNodeWithContentDescription("Image mode").performClick()
        onNode(hasScrollToIndexAction()).performScrollToIndex(2)
        onNode(hasSetTextAction()).assertTextContains(prompt)
        onNodeWithContentDescription("Send message").performScrollTo().performClick()
        runOnIdle { assertEquals(listOf(prompt), sent) }
        assertEquals("", onNode(hasSetTextAction()).fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }

    @Test
    fun focusedInlineComposerKeepsItsWholeCardVisibleWhenKeyboardShrinksViewport() = runComposeUiTest {
        var viewportHeight by mutableStateOf(800.dp)
        setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f),
                LocalCreateSafeDrawingInsetsOverride provides WindowInsets(0),
                LocalNavigationMenuAction provides {},
            ) {
                MaterialTheme {
                    ChatScreenContent(
                        uiState = ChatUiState.Ready(),
                        streamingState = StreamingState(),
                        onSelectModel = {}, onSendMessage = {}, onCancelGeneration = {}, onNavigateToSearch = {},
                        modifier = Modifier.requiredSize(390.dp, viewportHeight),
                    )
                }
            }
        }
        val prompt = "Write a story about a tiny astronaut who opens a noodle shop."
        onNodeWithText("Space noodles").performScrollTo().performClick()
        onNode(hasSetTextAction()).assertIsFocused().assertTextContains(prompt)
        runOnIdle { viewportHeight = 375.dp }
        waitForIdle()
        onNode(hasSetTextAction()).assertIsFocused().assertTextContains(prompt)
        onNodeWithContentDescription("Select model").assertIsDisplayed()
        onNodeWithContentDescription("Send message").assertIsDisplayed()
        val viewport = onNodeWithTag("create-route-canvas").fetchSemanticsNode().boundsInRoot
        val composer = onNodeWithTag("create-command").fetchSemanticsNode().boundsInRoot
        assertTrue(composer.top >= viewport.top && composer.bottom <= viewport.bottom,
            "The entire composer must stay above the keyboard: composer=$composer viewport=$viewport")
    }

    @Test
    fun stoppedReplyRecoverySendsFollowupsAndNewIdeaKeepsHistory() = runComposeUiTest {
        val sent = mutableListOf<String>()
        val prompt = "Tell me a moon story"
        val partial = "The little astronaut opened the door."
        setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f),
                LocalCreateSafeDrawingInsetsOverride provides WindowInsets(0),
                LocalNavigationMenuAction provides {},
            ) {
                MaterialTheme {
                    ChatScreenContent(
                        uiState = ChatUiState.Ready(messages = persistentListOf(
                            ChatMessage("user", MessageRole.User, prompt),
                            ChatMessage("reply", MessageRole.Assistant, partial, delivery = MessageDelivery.Stopped),
                        )),
                        streamingState = StreamingState(),
                        onSelectModel = {}, onSendMessage = sent::add, onCancelGeneration = {}, onNavigateToSearch = {},
                        modifier = Modifier.requiredSize(390.dp, 800.dp),
                    )
                }
            }
        }
        onNode(hasScrollToIndexAction()).performScrollToIndex(2)
        onNodeWithText("Continue").performScrollTo().performClick()
        onNodeWithText("Try again").performClick()
        runOnIdle { assertEquals(listOf("Please continue your previous reply.", prompt), sent) }
        onNode(hasSetTextAction()).performTextInput("An unsent draft")
        onNodeWithText("New idea").performClick()
        assertEquals("", onNode(hasSetTextAction()).fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        onNodeWithText(partial).performScrollTo().assertIsDisplayed()
        runOnIdle { assertEquals(2, sent.size, "Starting a draft must not send or delete the conversation") }
    }
}
