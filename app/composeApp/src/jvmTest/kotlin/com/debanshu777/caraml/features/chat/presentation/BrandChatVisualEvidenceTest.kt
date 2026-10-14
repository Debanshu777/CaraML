@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.features.chat.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.drawer.FocusModeController
import com.debanshu777.caraml.core.drawer.LocalFocusModeController
import com.debanshu777.caraml.core.drawer.LocalNavigationMenuAction
import com.debanshu777.caraml.core.theme.CaraMLTheme
import com.debanshu777.caraml.core.theme.ThemeMode
import com.debanshu777.caraml.core.theme.ThemePreferences
import com.debanshu777.caraml.core.ui.components.AuroraBackdrop
import com.debanshu777.caraml.features.chat.data.ChatMessage
import com.debanshu777.caraml.features.chat.data.MessageRole
import com.debanshu777.caraml.features.chat.data.MessageDelivery
import com.debanshu777.caraml.features.chat.data.LiveGenerationStats
import com.debanshu777.caraml.features.chat.presentation.components.providers.LocalModelPreviewProvider
import com.debanshu777.caraml.features.modelhub.presentation.search.saveModelHubEvidence
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test

class BrandChatVisualEvidenceTest {
    @Test
    fun lightEmptyCreateAt390() = renderConversation(ThemeMode.LIGHT, empty = true)

    @Test
    fun darkThinkingAt390() = renderConversation(ThemeMode.DARK, empty = false)

    @Test
    fun lightThinkingAt390() = renderConversation(ThemeMode.LIGHT, empty = false)

    @Test
    fun lightAnswerAt390() = renderConversation(ThemeMode.LIGHT, empty = false, completed = true)

    @Test
    fun lightLimitedAt320AndLargeText() = renderConversation(ThemeMode.LIGHT, empty = false, limited = true, width = 320, fontScale = 2f)

    private fun renderConversation(theme: ThemeMode, empty: Boolean, completed: Boolean = false, limited: Boolean = false, width: Int = 390, fontScale: Float = 1f) = runComposeUiTest {
        setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, fontScale),
                LocalCreateSafeDrawingInsetsOverride provides WindowInsets(0, 0, 0, 0),
                LocalNavigationMenuAction provides {},
                LocalFocusModeController provides FocusModeController(),
            ) {
                CaraMLTheme(ThemePreferences(themeMode = theme, reduceMotion = true)) {
                    Box(Modifier.requiredSize(width.dp, 740.dp).testTag("visual-root")) {
                        AuroraBackdrop {
                            ChatScreenContent(
                                uiState = ChatUiState.Ready(
                                    messages = if (empty) persistentListOf() else persistentListOf(
                                        ChatMessage(id = "prompt", role = MessageRole.User, text = "Explain dark matter in detail."),
                                        ChatMessage(id = "reply", role = MessageRole.Assistant,
                                            text = if (completed) "Dark matter is matter we detect through gravity.\n\n## How we infer its presence\n\nIts gravitational effects appear in galaxy rotation and gravitational lensing. The final answer now has its own space in the conversation." else "",
                                            thinking = if (completed || limited) "A short reasoning summary." else null,
                                            delivery = if (completed) MessageDelivery.Complete else if (limited) MessageDelivery.TokenLimit else null),
                                    ),
                                    isGenerating = !empty && !completed && !limited,
                                    selectedModel = LocalModelPreviewProvider().values.first(),
                                ),
                                streamingState = if (empty || completed || limited) StreamingState() else StreamingState(
                                    streamingMessageId = "reply", streamingThinkingText = "A quiet moon, a tiny kitchen, and an unexpected first customer…",
                                    liveStats = LiveGenerationStats(128, 512, 128, 7.5),
                                ),
                                onSelectModel = {}, onSendMessage = {}, onCancelGeneration = {}, onNavigateToSearch = {},
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
        onNodeWithText(if (empty) "Got a\nweird idea?" else "CaraML").assertIsDisplayed()
        saveModelHubEvidence("chat-${theme.name.lowercase()}-${if (empty) "ready" else if (completed) "answer" else if (limited) "limited" else "thinking"}-$width-${fontScale}.png")
    }
}
