@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.features.chat.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.debanshu777.caraml.features.chat.domain.GenerationMode
import com.debanshu777.caraml.features.chat.presentation.components.ChatInputBar
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
