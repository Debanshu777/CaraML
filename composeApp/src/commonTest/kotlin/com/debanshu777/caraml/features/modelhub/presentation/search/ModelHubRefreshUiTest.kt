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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.ui.motion.LocalAuroraMotionPolicy
import kotlin.test.Test
import kotlin.test.assertEquals

class ModelHubRefreshUiTest {

    @Test
    fun refreshKeepsKeyedRowCompositionAndOneShotEntryState() = runComposeUiTest {
        var refreshing by mutableStateOf(false)
        var compositionInstances = 0
        var activeCompositions = 0
        var disposedCompositions = 0
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
                            remember(model) { compositionInstances += 1 }
                            DisposableEffect(model) {
                                activeCompositions += 1
                                onDispose {
                                    activeCompositions -= 1
                                    disposedCompositions += 1
                                }
                            }
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

        mainClock.advanceTimeByFrame()
        runOnIdle {
            assertEquals(1, compositionInstances)
            assertEquals(1, activeCompositions)
            assertEquals(0, disposedCompositions)
        }

        runOnIdle { refreshing = true }
        mainClock.advanceTimeByFrame()
        runOnIdle {
            assertEquals(1, compositionInstances, "Refresh must not create a new row instance")
            assertEquals(1, activeCompositions, "The keyed row must remain continuously composed")
            assertEquals(0, disposedCompositions, "Refresh must not dispose the existing row")
        }
        onNodeWithText("Retained model").assertIsDisplayed()
        onNodeWithContentDescription("Refreshing model results").assertIsDisplayed()

        runOnIdle { refreshing = false }
        mainClock.advanceTimeByFrame()
        runOnIdle {
            assertEquals(1, compositionInstances, "Completing refresh must not replay row entry")
            assertEquals(1, activeCompositions)
            assertEquals(0, disposedCompositions)
        }
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
