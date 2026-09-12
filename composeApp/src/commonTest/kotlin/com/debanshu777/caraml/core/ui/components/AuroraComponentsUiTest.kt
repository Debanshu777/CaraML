@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.core.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AuroraComponentsUiTest {

    @Test
    fun emptyStateExposesItsActionAndInvokesIt() = runComposeUiTest {
        var clicks = 0
        setContent {
            MaterialTheme {
                CaraMLEmptyState(
                    icon = Icons.Default.AutoAwesome,
                    title = "Think locally. Stay private.",
                    supportingText = "Your prompt and model stay on this device.",
                    actionLabel = "Browse models",
                    onAction = { clicks += 1 },
                )
            }
        }

        onNodeWithText("Think locally. Stay private.").assertIsDisplayed()
        onNodeWithText("Browse models").performClick()
        runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun statusPillMeaningDoesNotDependOnColor() = runComposeUiTest {
        setContent {
            MaterialTheme {
                CaraMLStatusPill(
                    label = "Ready",
                    contentDescription = "Model ready for chat",
                    tone = StatusTone.Success,
                    icon = Icons.Default.CheckCircle,
                )
            }
        }

        onNodeWithContentDescription("Model ready for chat")
            .assertIsDisplayed()
            .assertTextEquals("Ready")
    }
}
