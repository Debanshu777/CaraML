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
import com.debanshu777.caraml.features.modelhub.presentation.search.saveModelHubEvidence
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test

class BrandChatVisualEvidenceTest {
    @Test
    fun lightEmptyCreateAt390() = renderConversation(ThemeMode.LIGHT, empty = true)

    @Test
    fun darkThinkingAt390() = renderConversation(ThemeMode.DARK, empty = false)

    private fun renderConversation(theme: ThemeMode, empty: Boolean) = runComposeUiTest {
        setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f),
                LocalCreateSafeDrawingInsetsOverride provides WindowInsets(0, 0, 0, 0),
                LocalNavigationMenuAction provides {},
                LocalFocusModeController provides FocusModeController(),
            ) {
                CaraMLTheme(ThemePreferences(themeMode = theme, reduceMotion = true)) {
                    Box(Modifier.requiredSize(390.dp, 740.dp).testTag("visual-root")) {
                        AuroraBackdrop {
                            ChatScreenContent(
                                uiState = ChatUiState.Ready(
                                    messages = if (empty) persistentListOf() else persistentListOf(
                                        ChatMessage(id = "prompt", role = MessageRole.User, text = "Write a tiny story about a noodle shop on the moon."),
                                        ChatMessage(id = "reply", role = MessageRole.Assistant, text = ""),
                                    ),
                                    isGenerating = !empty,
                                ),
                                streamingState = if (empty) StreamingState() else StreamingState(
                                    streamingMessageId = "reply", streamingThinkingText = "A quiet moon, a tiny kitchen, and an unexpected first customer…",
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
        saveModelHubEvidence(if (empty) "brand-chat-ready.png" else "brand-chat-thinking.png")
    }
}
