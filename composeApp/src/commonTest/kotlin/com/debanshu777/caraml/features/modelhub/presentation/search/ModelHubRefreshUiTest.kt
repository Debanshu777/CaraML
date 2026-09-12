@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.features.modelhub.presentation.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.ui.motion.LocalAuroraMotionPolicy
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class ModelHubRefreshUiTest {

    @Test
    fun refreshRetainsKeyedRowsWithoutReplayingTheirEntry() = runComposeUiTest {
        var refreshing by mutableStateOf(false)
        mainClock.autoAdvance = false

        setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(width = 360.dp, height = 240.dp)) {
                    val motion = LocalAuroraMotionPolicy.current
                    LazyColumn {
                        modelHubResultItems(
                            isLoading = refreshing,
                            hasResponse = true,
                            errorMessage = null,
                            models = listOf("Retained model"),
                            itemKey = { it },
                            blockingLoadingKey = "blocking",
                            refreshLoadingKey = "refresh",
                            errorKey = "error",
                            emptyKey = "empty",
                            blockingLoadingDescription = "Loading model results",
                            refreshLoadingDescription = "Refreshing model results",
                            emptyMessage = "No models",
                            motion = motion,
                        ) { model, itemModifier ->
                            Box(
                                modifier = itemModifier
                                    .fillMaxWidth()
                                    .height(64.dp)
                                    .padding(12.dp),
                            ) {
                                Text(model)
                            }
                        }
                    }
                }
            }
        }

        mainClock.advanceTimeBy(300)
        mainClock.advanceTimeByFrame()
        val settledY = onNodeWithText("Retained model").fetchSemanticsNode().positionInRoot.y

        runOnIdle { refreshing = true }
        mainClock.advanceTimeByFrame()
        onNodeWithText("Retained model").assertIsDisplayed()
        onNodeWithContentDescription("Refreshing model results").assertIsDisplayed()
        val refreshStartY = onNodeWithText("Retained model").fetchSemanticsNode().positionInRoot.y
        mainClock.advanceTimeBy(100)
        val refreshMidY = onNodeWithText("Retained model").fetchSemanticsNode().positionInRoot.y

        assertTrue(abs(settledY - refreshStartY) < 0.1f, "Refresh must retain the keyed row")
        assertTrue(
            abs(refreshStartY - refreshMidY) < 0.1f,
            "A retained row must not replay entry placement during refresh",
        )

        runOnIdle { refreshing = false }
        mainClock.advanceTimeByFrame()
        onNodeWithContentDescription("Refreshing model results").assertDoesNotExist()
        onNodeWithText("Retained model").assertIsDisplayed()
    }

    @Test
    fun blockingLoaderIsOnlyUsedWithoutPriorResponse() = runComposeUiTest {
        setContent {
            MaterialTheme {
                val motion = LocalAuroraMotionPolicy.current
                LazyColumn {
                    modelHubResultItems(
                        isLoading = true,
                        hasResponse = false,
                        errorMessage = null,
                        models = emptyList<String>(),
                        itemKey = { it },
                        blockingLoadingKey = "blocking",
                        refreshLoadingKey = "refresh",
                        errorKey = "error",
                        emptyKey = "empty",
                        blockingLoadingDescription = "Loading model results",
                        refreshLoadingDescription = "Refreshing model results",
                        emptyMessage = "No models",
                        motion = motion,
                    ) { model, itemModifier ->
                        Text(model, modifier = itemModifier)
                    }
                }
            }
        }

        onNodeWithContentDescription("Loading model results").assertIsDisplayed()
        onNodeWithContentDescription("Refreshing model results").assertDoesNotExist()
    }
}
