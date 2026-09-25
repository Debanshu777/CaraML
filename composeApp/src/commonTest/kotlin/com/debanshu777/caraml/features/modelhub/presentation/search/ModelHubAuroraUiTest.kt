@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.features.modelhub.presentation.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.ui.components.CaraMLStatusPill
import com.debanshu777.caraml.core.ui.components.StatusTone
import com.debanshu777.caraml.core.recommendation.RecommendationProfile
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubOverview
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelResultCard
import com.debanshu777.caraml.core.rating.ui.RecommendationStatusChip
import com.debanshu777.caraml.features.modelhub.domain.DescriptorState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelHubAuroraUiTest {

    @Test
    fun darkSearchSummaryUsesReadableThemeContentColor() = runComposeUiTest {
        val scheme = darkColorScheme()
        setContent {
            MaterialTheme(colorScheme = scheme) {
                Box(
                    Modifier
                        .width(360.dp)
                        .background(scheme.surface)
                        .testTag("search-summary-root"),
                ) {
                    SearchResultsSummary(
                        query = "MiniCPM5",
                        resultCount = 552,
                        onClear = {},
                    )
                }
            }
        }

        val bounds = onNodeWithText("Results for “MiniCPM5” · 552")
            .fetchSemanticsNode().boundsInRoot
        val pixels = onNodeWithTag("search-summary-root", useUnmergedTree = true)
            .captureToImage()
            .toPixelMap()
        var brightestTextPixel = 0f
        for (y in bounds.top.toInt() until bounds.bottom.toInt()) {
            for (x in bounds.left.toInt() until bounds.right.toInt()) {
                brightestTextPixel = maxOf(brightestTextPixel, pixels[x, y].luminance())
            }
        }
        assertTrue(
            brightestTextPixel > 0.5f,
            "Dark-theme result summary must paint readable text; max luminance was $brightestTextPixel",
        )
    }

    @Test
    fun darkModelContextUsesReadableSemanticIconColor() = runComposeUiTest {
        val surface = Color(0xFF07191D)
        val scheme = darkColorScheme(
            surface = surface,
            onSurfaceVariant = Color(0xFFB8CACA),
        )
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme(colorScheme = scheme) {
                    Box(
                        Modifier
                            .width(360.dp)
                            .background(surface),
                    ) {
                        ModelHubOverview(
                            storageInfo = StorageInfoUiState(
                                totalDeviceBytes = 8_589_934_592L,
                                availableDeviceBytes = 6_442_450_944L,
                                usedByModelsBytes = 2_147_483_648L,
                            ),
                            profile = null,
                            onOpenProfile = null,
                        )
                    }
                }
            }
        }

        val context = onNodeWithTag("model-context").fetchSemanticsNode().boundsInRoot
        val storage = onNodeWithText("Storage", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val pixels = onNodeWithTag("model-context").captureToImage().toPixelMap()
        val iconRight = (storage.left - context.left - 2f).toInt().coerceAtLeast(1)
        var iconContrast = 1f
        for (y in 0 until pixels.height) {
            for (x in 0 until minOf(iconRight, pixels.width)) {
                iconContrast = maxOf(iconContrast, contrastRatio(pixels[x, y], surface))
            }
        }
        assertTrue(iconContrast >= 3f, "Storage icon contrast was $iconContrast:1")
    }

    @Test
    fun modelResultRowKeepsTechnicalHierarchyAndTrailingState() = runComposeUiTest {
        var opened = 0
        setContent {
            MaterialTheme {
                ModelResultCard(
                    title = "org/tiny-model",
                    author = "org",
                    metadata = "Text generation · 1.2 GB",
                    status = { Text("Recommended") },
                    onClick = { opened += 1 },
                    trailing = { Text("Download") },
                )
            }
        }

        onNodeWithText("tiny-model").assertIsDisplayed()
        onNodeWithText("org").assertIsDisplayed()
        onNodeWithText("Text generation · 1.2 GB").assertIsDisplayed()
        onNodeWithText("Recommended").assertIsDisplayed()
        onNodeWithText("Download").assertIsDisplayed()
        val titleY = onNodeWithText("tiny-model", useUnmergedTree = true)
            .fetchSemanticsNode().positionInRoot.y
        val authorY = onNodeWithText("org", useUnmergedTree = true)
            .fetchSemanticsNode().positionInRoot.y
        val titleBounds = onNodeWithText("tiny-model", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val statusBounds = onNodeWithText("Recommended", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val metadataY = onNodeWithText("Text generation · 1.2 GB", useUnmergedTree = true)
            .fetchSemanticsNode().positionInRoot.y
        assertTrue(authorY < titleY)
        assertTrue(titleY < metadataY)
        assertTrue(statusBounds.left == titleBounds.left)
        assertTrue(statusBounds.top >= metadataY)
        onNodeWithContentDescription("Open model org/tiny-model")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        runOnIdle { assertEquals(1, opened) }
    }

    @Test
    fun passiveRecommendationStatusUsesCompactPaintedHeight() = runComposeUiTest {
        setContent {
            MaterialTheme {
                RecommendationStatusChip(
                    state = DescriptorState.NEEDS_INFORMATION,
                    recommendation = null,
                    modifier = Modifier.testTag("passive-status"),
                )
            }
        }

        val height = onNodeWithTag("passive-status").fetchSemanticsNode().boundsInRoot.height
        assertTrue(height <= 36f, "Passive status paint should stay compact; height was $height")
        onNodeWithText("Needs information").assertIsDisplayed()
    }

    @Test
    fun overviewProfileActionRetainsItsAccessibleSelectionSummary() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ModelHubOverview(
                    storageInfo = StorageInfoUiState(),
                    profile = RecommendationProfile(),
                    onOpenProfile = {},
                )
            }
        }

        onNodeWithContentDescription(
            "Recommendation profile. Selected risk: Balanced. Selected priority: Balanced. " +
                "Open profile controls.",
        ).assertIsDisplayed()
    }

    @Test
    fun modelHubContextAndRegistryRowRemainReachableAtTwoHundredPercentFontScale() =
        runComposeUiTest {
            setContent {
                AtTwoHundredPercentFontScale {
                    MaterialTheme {
                        Box(Modifier.width(360.dp).height(220.dp)) {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                Spacer(Modifier.height(240.dp))
                                ModelHubOverview(
                                    storageInfo = StorageInfoUiState(
                                        totalDeviceBytes = 4_294_967_296L,
                                        availableDeviceBytes = 2_147_483_648L,
                                        usedByModelsBytes = 2_147_483_648L,
                                    ),
                                    profile = RecommendationProfile(),
                                    onOpenProfile = {},
                                )
                                ModelResultCard(
                                    title = "org/large-text-model",
                                    author = "org",
                                    metadata = "Text generation · 1.2 GB",
                                    status = {
                                        CaraMLStatusPill(
                                            label = "Recommended",
                                            contentDescription = "Recommended status",
                                            tone = StatusTone.Accent,
                                            icon = Icons.Default.AutoAwesome,
                                        )
                                    },
                                    onClick = {},
                                    trailing = { Text("Download") },
                                )
                            }
                        }
                    }
                }
            }

            onNodeWithTag("model-context").performScrollTo().assertIsDisplayed()
            onNodeWithText("Device profile").performClick()
            onNodeWithText("Storage").performScrollTo().assertIsDisplayed()
            val title = onNodeWithText("large-text-model", useUnmergedTree = true)
            val status = onNodeWithText("Recommended", useUnmergedTree = true)
            val action = onNodeWithText("Download", useUnmergedTree = true)

            title
                .performScrollTo()
                .assertIsDisplayed()
            status
                .performScrollTo()
                .assertIsDisplayed()
            action
                .performScrollTo()
                .assertIsDisplayed()
        }
}

private fun contrastRatio(first: Color, second: Color): Float {
    val lighter = maxOf(first.luminance(), second.luminance())
    val darker = minOf(first.luminance(), second.luminance())
    return (lighter + 0.05f) / (darker + 0.05f)
}

@Composable
private fun AtTwoHundredPercentFontScale(content: @Composable () -> Unit) {
    val current = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(current.density, fontScale = 2f),
        content = content,
    )
}
