@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.core.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
        onNodeWithText("Browse models")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
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

    @Test
    fun emptyStateTitleAndActionRemainVisibleAtTwoHundredPercentFontScale() = runComposeUiTest {
        setContent {
            AtTwoHundredPercentFontScale {
                MaterialTheme {
                    Box(Modifier.width(320.dp).height(360.dp)) {
                        CaraMLEmptyState(
                            icon = Icons.Default.AutoAwesome,
                            title = "Think locally. Stay private.",
                            supportingText = "Your prompt and model stay on this device.",
                            actionLabel = "Browse models",
                            onAction = {},
                        )
                    }
                }
            }
        }

        onNodeWithText("Think locally. Stay private.").assertIsDisplayed()
        onNodeWithText("Browse models").assertIsDisplayed()
    }

    @Test
    fun semanticStatusMatrixNeverDependsOnColorAlone() = runComposeUiTest {
        val cases = listOf(
            StatusCase("Ready", "Ready status", StatusTone.Success, Icons.Default.CheckCircle),
            StatusCase("Partial", "Partial status", StatusTone.Warning, Icons.Default.Build),
            StatusCase("Unsupported", "Unsupported status", StatusTone.Error, Icons.Default.Block),
            StatusCase("Recommended", "Recommended status", StatusTone.Accent, Icons.Default.AutoAwesome),
            StatusCase("Risky", "Risky status", StatusTone.Warning, Icons.Default.Warning),
            StatusCase("Downloading", "Downloading status", StatusTone.Neutral, Icons.Default.Download),
            StatusCase("Generating", "Generating status", StatusTone.Accent, Icons.Default.Sync),
            StatusCase("Success", "Success status", StatusTone.Success, Icons.Default.CheckCircle),
            StatusCase("Error", "Error status", StatusTone.Error, Icons.Default.Error),
        )
        setContent {
            MaterialTheme {
                Column {
                    cases.forEach { status ->
                        CaraMLStatusPill(
                            label = status.label,
                            contentDescription = status.description,
                            tone = status.tone,
                            icon = status.icon,
                        )
                    }
                }
            }
        }

        cases.forEach { status ->
            onNodeWithContentDescription(status.description)
                .assertIsDisplayed()
                .assertTextEquals(status.label)
        }
    }
}

private data class StatusCase(
    val label: String,
    val description: String,
    val tone: StatusTone,
    val icon: ImageVector,
)

@Composable
private fun AtTwoHundredPercentFontScale(content: @Composable () -> Unit) {
    val current = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(current.density, fontScale = 2f),
        content = content,
    )
}
