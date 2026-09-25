@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.features.modelhub.presentation.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import com.debanshu777.caraml.core.ui.motion.LocalAuroraMotionPolicy
import com.debanshu777.caraml.core.ui.motion.auroraMotionPolicy
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelHubRefreshUiTest {

    @Test
    fun initialAndReplacementRowsAreFullyOpaqueOnTheirFirstRenderedFrame() = runComposeUiTest {
        val background = Color.Black
        val initialColor = Color(0xFFD34A4A)
        val replacementColor = Color(0xFF4F83FF)
        var rows by mutableStateOf(listOf(ColorRow("old", initialColor)))
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme {
                    Box(
                        Modifier
                            .requiredSize(width = 240.dp, height = 96.dp)
                            .background(background)
                            .testTag("replacement-host"),
                    ) {
                        LazyColumn {
                            modelHubResultItems(
                                isLoading = false,
                                hasResponse = true,
                                errorMessage = null,
                                models = rows,
                                itemKey = ColorRow::id,
                                blockingLoadingKey = "blocking",
                                refreshLoadingKey = "refresh",
                                errorKey = "error",
                                emptyKey = "empty",
                                blockingLoadingDescription = "Loading",
                                refreshLoadingDescription = "Refreshing",
                                emptyMessage = "Empty",
                                motion = auroraMotionPolicy(durationScale = 1f),
                            ) { row, itemModifier ->
                                Box(
                                    itemModifier
                                        .height(64.dp)
                                        .background(row.color)
                                        .testTag("replacement:${row.id}"),
                                )
                            }
                        }
                    }
                }
            }
        }

        mainClock.advanceTimeByFrame()
        assertRowCenterColor(
            hostTag = "replacement-host",
            rowTag = "replacement:old",
            expected = initialColor,
        )
        mainClock.advanceTimeBy(300)
        runOnIdle { rows = listOf(ColorRow("new", replacementColor)) }
        mainClock.advanceTimeByFrame()

        assertRowCenterColor(
            hostTag = "replacement-host",
            rowTag = "replacement:new",
            expected = replacementColor,
        )
    }

    @Test
    fun userFilteringMayAnimateSurvivingRowPlacement() = runComposeUiTest {
        var rows by mutableStateOf(listOf("first", "second"))
        val survivingColor = Color(0xFF19B97A)
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme {
                    Box(
                        Modifier
                            .requiredSize(width = 240.dp, height = 120.dp)
                            .background(Color.Black)
                            .testTag("placement-host"),
                    ) {
                        LazyColumn {
                            modelHubResultItems(
                                isLoading = false,
                                hasResponse = true,
                                errorMessage = null,
                                models = rows,
                                itemKey = { it },
                                blockingLoadingKey = "blocking",
                                refreshLoadingKey = "refresh",
                                errorKey = "error",
                                emptyKey = "empty",
                                blockingLoadingDescription = "Loading",
                                refreshLoadingDescription = "Refreshing",
                                emptyMessage = "Empty",
                                motion = auroraMotionPolicy(durationScale = 1f),
                            ) { row, itemModifier ->
                                Box(
                                    itemModifier
                                        .height(48.dp)
                                        .background(
                                            if (row == "second") survivingColor else Color(0xFFD34A4A),
                                        )
                                        .testTag("placement:$row"),
                                )
                            }
                        }
                    }
                }
            }
        }

        mainClock.advanceTimeBy(300)
        mainClock.advanceTimeByFrame()
        runOnIdle { rows = listOf("second") }
        mainClock.advanceTimeBy(90)
        mainClock.advanceTimeByFrame()

        val movingCenter = onNodeWithTag("placement-host")
            .captureToImage()
            .toPixelMap()
            .averageYFor(survivingColor)
        assertTrue(
            movingCenter > 26f && movingCenter < 70f,
            "A surviving filtered row may animate from y=72 toward y=24; center was $movingCenter",
        )
        mainClock.advanceTimeBy(240)
        mainClock.advanceTimeByFrame()
        val settledCenter = onNodeWithTag("placement-host")
            .captureToImage()
            .toPixelMap()
            .averageYFor(survivingColor)
        assertTrue(abs(settledCenter - 23.5f) <= 1f)
    }

    @Test
    fun reducedMotionFilteringSnapsSurvivingRowWithoutSpatialMovement() = runComposeUiTest {
        var rows by mutableStateOf(listOf("first", "second"))
        val survivingColor = Color(0xFF19B97A)
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme {
                    Box(
                        Modifier
                            .requiredSize(width = 240.dp, height = 120.dp)
                            .background(Color.Black)
                            .testTag("reduced-host"),
                    ) {
                        LazyColumn {
                            modelHubResultItems(
                                isLoading = false,
                                hasResponse = true,
                                errorMessage = null,
                                models = rows,
                                itemKey = { it },
                                blockingLoadingKey = "blocking",
                                refreshLoadingKey = "refresh",
                                errorKey = "error",
                                emptyKey = "empty",
                                blockingLoadingDescription = "Loading",
                                refreshLoadingDescription = "Refreshing",
                                emptyMessage = "Empty",
                                motion = auroraMotionPolicy(durationScale = 0f),
                            ) { row, itemModifier ->
                                Box(
                                    itemModifier
                                        .height(48.dp)
                                        .background(
                                            if (row == "second") survivingColor else Color(0xFFD34A4A),
                                        )
                                        .testTag("reduced:$row"),
                                )
                            }
                        }
                    }
                }
            }
        }

        mainClock.advanceTimeByFrame()
        runOnIdle { rows = listOf("second") }
        mainClock.advanceTimeByFrame()

        val filteredCenter = onNodeWithTag("reduced-host")
            .captureToImage()
            .toPixelMap()
            .averageYFor(survivingColor)
        assertTrue(
            abs(filteredCenter - 23.5f) <= 1f,
            "Reduced motion must place the row at its settled position immediately",
        )
    }

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
        onNodeWithTag("model-results").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                "Model results loaded",
            ),
        )
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

private data class ColorRow(
    val id: String,
    val color: Color,
)

private fun Color.colorDistance(other: Color): Float =
    abs(red - other.red) + abs(green - other.green) + abs(blue - other.blue)

private fun androidx.compose.ui.test.ComposeUiTest.assertRowCenterColor(
    hostTag: String,
    rowTag: String,
    expected: Color,
) {
    val host = onNodeWithTag(hostTag)
    val hostBounds = host.fetchSemanticsNode().boundsInRoot
    val rowBounds = onNodeWithTag(rowTag).fetchSemanticsNode().boundsInRoot
    val pixels = host.captureToImage().toPixelMap()
    val sample = pixels[
        (rowBounds.center.x - hostBounds.left).toInt(),
        (rowBounds.center.y - hostBounds.top).toInt(),
    ]
    assertTrue(
        sample.colorDistance(expected) <= 0.03f,
        "$rowTag must be fully opaque on its first rendered frame; sampled $sample",
    )
}

private fun PixelMap.averageYFor(target: Color): Float {
    var count = 0
    var yTotal = 0L
    for (y in 0 until height) {
        for (x in 0 until width) {
            if (this[x, y].colorDistance(target) <= 0.08f) {
                count += 1
                yTotal += y
            }
        }
    }
    assertTrue(count > 0, "Expected rendered pixels for $target")
    return yTotal.toFloat() / count
}
