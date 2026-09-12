@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.features.modelhub.presentation.search

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
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
        onNodeWithContentDescription("Open model org/tiny-model").performClick()
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
}
