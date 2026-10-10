@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package com.debanshu777.caraml.features.chat.presentation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.debanshu777.caraml.features.chat.presentation.components.ChatInputBar
import com.debanshu777.caraml.features.chat.domain.GenerationMode
import kotlin.test.assertEquals
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.features.chat.data.ChatMessage
import com.debanshu777.caraml.features.chat.data.LiveGenerationStats
import com.debanshu777.caraml.features.chat.data.MessageRole
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import com.debanshu777.caraml.features.chat.data.MessageDelivery
import com.debanshu777.caraml.features.chat.presentation.components.MessageBubble
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.LiveRegionMode

class ChatReplyRegressionUiTest {
    @Test
    fun emptyAndTruncatedOutcomesAnnounceTheirTerminalNotice() = runComposeUiTest {
        var delivery by mutableStateOf(MessageDelivery.NoAnswer)
        setContent {
            MaterialTheme {
                MessageBubble(
                    message = ChatMessage("reply", MessageRole.Assistant, "", thinking = "Reasoning", delivery = delivery),
                )
            }
        }
        listOf(
            MessageDelivery.NoAnswer to "The model finished without an answer",
            MessageDelivery.TokenLimit to "Length limit reached before an answer",
            MessageDelivery.ContextLimit to "Context filled before an answer",
        ).forEach { (outcome, notice) ->
            runOnIdle { delivery = outcome }
            onNodeWithText(notice).assert(SemanticsMatcher.expectValue(
                SemanticsProperties.LiveRegion,
                LiveRegionMode.Polite,
            ))
        }
    }

    @Test
    fun thinkingConversationKeepsOneActivityAndHidesModesAndTechnicalStats() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalCreateSafeDrawingInsetsOverride provides WindowInsets(0)) {
                MaterialTheme {
                    ChatScreenContent(
                        uiState = ChatUiState.Ready(messages = persistentListOf(
                            ChatMessage("prompt", MessageRole.User, "Explain dark matter in detail"),
                            ChatMessage("reply", MessageRole.Assistant, ""),
                        ), isGenerating = true),
                        streamingState = StreamingState(streamingMessageId = "reply", streamingThinkingText = "Private reasoning",
                            liveStats = LiveGenerationStats(128, 512, 128, 7.5)),
                        onSelectModel = {}, onSendMessage = {}, onCancelGeneration = {}, onNavigateToSearch = {},
                        modifier = Modifier.requiredSize(390.dp, 740.dp),
                    )
                }
            }
        }
        onNodeWithText("One little moment").assertDoesNotExist()
        onNodeWithText("Live output").assertDoesNotExist()
        onNodeWithText("128/∞").assertDoesNotExist()
        onNodeWithText("Imagine").assertDoesNotExist()
        onNodeWithText("Private reasoning").assertDoesNotExist()
        onNodeWithTag("chat-model-picker").assertIsDisplayed()
    }
    @Test
    fun detailsRemainAccessibleDuringGenerationWithoutResizingTheComposerAtLargeText() = runComposeUiTest {
        var stats by mutableStateOf(LiveGenerationStats(7, 512, 7, 7.5))
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                MaterialTheme {
                    ChatInputBar(
                        generationMode = GenerationMode.Text, isConversation = true, isGenerating = true,
                        selectedModel = null, topModels = persistentListOf(), onSelectModel = {},
                        onDownloadModelClick = {}, onSendMessage = {}, onCancelGeneration = {},
                        liveStats = stats, modifier = Modifier.requiredSize(320.dp, 200.dp),
                    )
                }
            }
        }
        val before = onNodeWithContentDescription("Stop generation").fetchSemanticsNode().boundsInRoot
        runOnIdle { stats = LiveGenerationStats(127, 512, 127, 7.5) }
        assertEquals(before, onNodeWithContentDescription("Stop generation").fetchSemanticsNode().boundsInRoot)
        onNodeWithContentDescription("Generation details").performClick()
        onNodeWithText("127 tokens").assertIsDisplayed()
        onNodeWithText("Context: 127 / 512 tokens").assertIsDisplayed()
        onNodeWithText("Close").performClick()
        assertEquals(before, onNodeWithContentDescription("Stop generation").fetchSemanticsNode().boundsInRoot)
    }

}
