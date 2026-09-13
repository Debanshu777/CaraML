@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.features.modelhub.presentation.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.onNodeWithContentDescription
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelHubAuroraUiTest {

    @Test
    fun modelResultCardKeepsTitleStatusMetadataAndActionOrder() = runComposeUiTest {
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

        onNodeWithText("org/tiny-model").assertIsDisplayed()
        onNodeWithText("org").assertIsDisplayed()
        onNodeWithText("Text generation · 1.2 GB").assertIsDisplayed()
        onNodeWithText("Recommended").assertIsDisplayed()
        onNodeWithText("Download").assertIsDisplayed()
        val titleY = onNodeWithText("org/tiny-model", useUnmergedTree = true)
            .fetchSemanticsNode().positionInRoot.y
        val authorY = onNodeWithText("org", useUnmergedTree = true)
            .fetchSemanticsNode().positionInRoot.y
        val statusY = onNodeWithText("Recommended", useUnmergedTree = true)
            .fetchSemanticsNode().positionInRoot.y
        val metadataY = onNodeWithText("Text generation · 1.2 GB", useUnmergedTree = true)
            .fetchSemanticsNode().positionInRoot.y
        val actionY = onNodeWithText("Download", useUnmergedTree = true)
            .fetchSemanticsNode().positionInRoot.y
        assertTrue(titleY < authorY)
        assertTrue(authorY < statusY)
        assertTrue(statusY < metadataY)
        assertTrue(metadataY < actionY)
        onNodeWithContentDescription("Open model org/tiny-model")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        runOnIdle { assertEquals(1, opened) }
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
    fun modelHubCardAndProgressRemainReachableAtTwoHundredPercentFontScale() =
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

            onNodeWithText("Models: 2 GB")
                .performScrollTo()
                .assertIsDisplayed()
            onNodeWithContentDescription("Open model org/large-text-model")
                .performScrollTo()
                .assertIsDisplayed()
            onNodeWithText("org/large-text-model").assertIsDisplayed()
            onNodeWithText("Recommended").assertIsDisplayed()
            onNodeWithText("Download").assertIsDisplayed()
        }
}

@Composable
private fun AtTwoHundredPercentFontScale(content: @Composable () -> Unit) {
    val current = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(current.density, fontScale = 2f),
        content = content,
    )
}
