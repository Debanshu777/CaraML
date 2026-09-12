@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.features.chat.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.drawer.DrawerController
import com.debanshu777.caraml.core.drawer.GenerationModeController
import com.debanshu777.caraml.core.drawer.LocalDrawerController
import com.debanshu777.caraml.core.drawer.LocalGenerationModeController
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatBackdropLayeringUiTest {

    @Test
    fun destinationScaffoldLeavesBodyGapTransparent() = runComposeUiTest {
        val backdropColor = Color.Magenta

        setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .requiredSize(width = 320.dp, height = 480.dp)
                        .background(backdropColor)
                        .testTag("chat-backdrop-layer"),
                ) {
                    CompositionLocalProvider(
                        LocalDrawerController provides remember { DrawerController() },
                        LocalGenerationModeController provides remember {
                            GenerationModeController()
                        },
                    ) {
                        ChatScreenContent(
                            uiState = ChatUiState.NoModels,
                            streamingState = StreamingState(),
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

        val image = onNodeWithTag("chat-backdrop-layer").captureToImage()
        val bottomLeftBodyPixel = image.toPixelMap()[1, image.height - 1]

        assertEquals(backdropColor, bottomLeftBodyPixel)
    }
}
