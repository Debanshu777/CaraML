@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CaraMLTopBarUiTest {

    @Test
    fun legacyPositionalModifierAndTrailingActionsRemainCallable() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Column {
                    CaraMLTopBar(
                        "Artifact",
                        TopBarNavigation.Back,
                        {},
                        Modifier.testTag("legacy-details-header"),
                    ) {
                        Text("Details action")
                    }
                    CaraMLPrimaryTopBar(
                        "Models",
                        Modifier.testTag("legacy-primary-header"),
                    ) {
                        Text("Primary action")
                    }
                }
            }
        }

        onNodeWithTag("legacy-details-header").assertExists()
        onNodeWithTag("legacy-primary-header").assertExists()
    }

    @Test
    fun darkHeaderLeavesTheWorkspaceAtmosphereVisible() = runComposeUiTest {
        val backdrop = Color(0xFF24496B)
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme(
                    colorScheme = darkColorScheme(
                        surface = Color(0xFF101217),
                        onSurface = Color(0xFFF0F2FA),
                    ),
                ) {
                    HeaderTransparencyFixture(backdrop)
                }
            }
        }

        assertHeaderPixelMatches(backdrop)
    }

    @Test
    fun lightHeaderLeavesTheWorkspaceAtmosphereVisible() = runComposeUiTest {
        val backdrop = Color(0xFFE4D9F7)
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme(
                    colorScheme = lightColorScheme(
                        surface = Color(0xFFFFFBFF),
                        onSurface = Color(0xFF1B1B1F),
                    ),
                ) {
                    HeaderTransparencyFixture(backdrop)
                }
            }
        }

        assertHeaderPixelMatches(backdrop)
    }

    @Test
    fun backTargetKeepsItsAccessibleLabelAndCallback() = runComposeUiTest {
        var backClicks = 0
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme {
                    CaraMLTopBar(
                        title = "Artifact",
                        navigation = TopBarNavigation.Back,
                        onNavigationClick = { backClicks += 1 },
                    )
                }
            }
        }

        onNodeWithContentDescription("Navigate back")
            .assertIsDisplayed()
            .performClick()
        runOnIdle { assertEquals(1, backClicks) }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.assertHeaderPixelMatches(expected: Color) {
        val pixels = onNodeWithTag("header-host").captureToImage().toPixelMap()
        val sampled = pixels[300, 24]
        assertTrue(
            sampled.colorDistance(expected) <= 0.03f,
            "Header painted $sampled instead of preserving $expected",
        )
    }
}

@androidx.compose.runtime.Composable
private fun HeaderTransparencyFixture(backdrop: Color) {
    Box(
        modifier = Modifier
            .requiredSize(width = 360.dp, height = 96.dp)
            .background(backdrop)
            .testTag("header-host"),
    ) {
        CaraMLPrimaryTopBar(
            title = "Models",
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun Color.colorDistance(other: Color): Float =
    abs(red - other.red) + abs(green - other.green) + abs(blue - other.blue)
