@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.features.modelhub.presentation.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasImeAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.recommendation.RecommendationProfile
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubBrowseControls
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubHeader
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubOverview
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubStateKind
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubStateView
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelResultCard
import com.debanshu777.caraml.features.modelhub.presentation.search.components.SearchBar
import com.debanshu777.caraml.features.modelhub.presentation.search.components.SearchModelListItem
import com.debanshu777.huggingfacemanager.model.ModelSort
import com.debanshu777.huggingfacemanager.model.ParameterRange
import com.debanshu777.huggingfacemanager.model.SearchModelsResponse
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelHubRegistryUiTest {

    @Test
    fun summaryAndResultStateExposeStableRegionsAndAction() = runComposeUiTest {
        var retries = 0
        setContent {
            MaterialTheme {
                Column(Modifier.width(360.dp)) {
                    ModelHubHeader(
                        title = "Models",
                        summary = "24 models · 2 filters",
                    )
                    ModelHubStateView(
                        kind = ModelHubStateKind.Error,
                        message = "Models could not be loaded.",
                        actionLabel = "Retry",
                        onAction = { retries += 1 },
                    )
                }
            }
        }

        onNodeWithTag("model-summary").assertIsDisplayed()
        onNodeWithTag("model-results").assertIsDisplayed()
        onNodeWithText("Retry").performClick()
        runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun searchCommandHasOneSearchAffordanceAndConditionalClearAction() = runComposeUiTest {
        var query by mutableStateOf("")
        var searches = 0
        setContent {
            MaterialTheme {
                SearchBar(
                    query = query,
                    onQueryChange = { query = it },
                    onSearch = { searches += 1 },
                )
            }
        }

        onNodeWithTag("model-command").assertIsDisplayed()
        onAllNodesWithContentDescription("Search models").assertCountEquals(1)
        onAllNodesWithContentDescription("Submit model search").assertCountEquals(0)
        onNodeWithContentDescription("Clear model search").assertDoesNotExist()
        onNode(hasImeAction(ImeAction.Search)).performImeAction()
        runOnIdle { assertEquals(1, searches) }

        runOnIdle { query = "tinyllama" }
        onAllNodesWithContentDescription("Search models").assertCountEquals(1)
        onAllNodesWithContentDescription("Submit model search").assertCountEquals(0)
        onNodeWithContentDescription("Clear model search").assertIsDisplayed()
    }

    @Test
    fun contextStripIsShorterAndVisuallyQuieterThanTheSearchCommand() = runComposeUiTest {
        setContent {
            AtDensityOne {
                MaterialTheme {
                    Column(Modifier.width(360.dp)) {
                        SearchBar(
                            query = "model",
                            onQueryChange = {},
                            onSearch = {},
                        )
                        ModelHubOverview(
                            storageInfo = StorageInfoUiState(
                                totalDeviceBytes = 8_589_934_592L,
                                availableDeviceBytes = 6_442_450_944L,
                                usedByModelsBytes = 2_147_483_648L,
                            ),
                            profile = RecommendationProfile(),
                            onOpenProfile = {},
                        )
                    }
                }
            }
        }

        val commandHeight = onNodeWithTag("model-command").fetchSemanticsNode().boundsInRoot.height
        val contextHeight = onNodeWithTag("model-context").fetchSemanticsNode().boundsInRoot.height
        assertTrue(
            contextHeight < commandHeight,
            "Secondary context must be shorter than the command: context=$contextHeight, command=$commandHeight",
        )
    }

    @Test
    fun browseToolbarFitsOneHorizontalBandAt360dp() = runComposeUiTest {
        setContent {
            AtDensityOne {
                MaterialTheme {
                    Box(Modifier.width(360.dp)) {
                        ModelHubBrowseControls(
                            browseMode = ModelHubBrowseMode.LanguageModels,
                            onBrowseModeChange = {},
                            showSortFilters = true,
                            ordering = ModelOrdering.Server(ModelSort.TRENDING),
                            sort = ModelSort.TRENDING,
                            minParams = ParameterRange.ZERO,
                            maxParams = ParameterRange.SIX_B,
                            onSortChange = {},
                            onOrderingChange = {},
                            onMinParamsChange = {},
                            onMaxParamsChange = {},
                        )
                    }
                }
            }
        }

        onNodeWithTag("model-toolbar").assertIsDisplayed()
        val controlY = listOf("Text", "Image", "Video", "Sort", "Filters").map { label ->
            onNodeWithText(label, substring = true)
                .fetchSemanticsNode().boundsInRoot.center.y
        }
        assertTrue(
            controlY.max() - controlY.min() < 1f,
            "Every browse control must occupy one horizontal band; centers were $controlY",
        )
        onNodeWithText("Minimum parameters").assertDoesNotExist()
        onNodeWithText("Maximum parameters").assertDoesNotExist()
    }

    @Test
    fun oneLineModelRowIsAtMost148DpAndHasNoCardOutline() = runComposeUiTest {
        val pageColor = Color(0xFFF8FAFC)
        val oldCardColor = Color(0xFFB91C1C)
        val scheme = lightColorScheme(
            surface = pageColor,
            surfaceContainer = oldCardColor,
        )
        setContent {
            AtDensityOne {
                MaterialTheme(colorScheme = scheme) {
                    Box(
                        Modifier
                            .width(360.dp)
                            .background(pageColor),
                    ) {
                        ModelResultCard(
                            title = "org/tiny-model",
                            author = "org",
                            metadata = "GGUF · 1.2 GB",
                            status = { Text("Usable") },
                            onClick = {},
                        )
                    }
                }
            }
        }

        val row = onNodeWithTag("model-row:org/tiny-model")
        val bounds = row.fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.height <= 148f, "A simple registry row was ${bounds.height}dp tall")
        val pixels = row.captureToImage().toPixelMap()
        val neutralEdge = pixels[1, (pixels.height / 2).coerceAtLeast(1)]
        assertColorNear(pageColor, neutralEdge, "Registry rows must not paint independent card chrome at the page edge")
    }

    @Test
    fun longModelNameKeepsOwnerTitleStatusAndMetadataReadable() = runComposeUiTest {
        setContent {
            AtDensityOne {
                MaterialTheme {
                    Box(Modifier.width(360.dp)) {
                        ModelResultCard(
                            title = "research-lab/a-very-long-model-name-that-needs-two-lines-on-phone",
                            author = "research-lab",
                            metadata = "GGUF · text generation · 12.4 GB",
                            status = { Text("Needs information") },
                            onClick = {},
                        )
                    }
                }
            }
        }

        val owner = onNodeWithText("research-lab", useUnmergedTree = true)
        val title = onNodeWithText(
            "a-very-long-model-name-that-needs-two-lines-on-phone",
            useUnmergedTree = true,
        )
        val status = onNodeWithText("Needs information", useUnmergedTree = true)
        val metadata = onNodeWithText("GGUF · text generation · 12.4 GB", useUnmergedTree = true)
        owner.assertIsDisplayed()
        title.assertIsDisplayed()
        status.assertIsDisplayed()
        metadata.assertIsDisplayed()
        val titleBounds = title.fetchSemanticsNode().boundsInRoot
        val statusBounds = status.fetchSemanticsNode().boundsInRoot
        assertTrue(
            statusBounds.left > titleBounds.left,
            "Registry state should occupy a distinct trailing slot instead of a stacked card section",
        )
        assertTrue(
            titleBounds.right <= statusBounds.left || titleBounds.bottom <= statusBounds.top,
            "Long title and state must not overlap: title=$titleBounds status=$statusBounds",
        )
    }

    @Test
    fun recommendedRowUsesOneSignalRailRatherThanGradientFill() = runComposeUiTest {
        val railColor = Color(0xFF007A52)
        val selectedColor = Color(0xFFDCEFE7)
        val scheme = lightColorScheme(
            primary = railColor,
            surfaceContainerHigh = selectedColor,
        )
        setContent {
            AtDensityOne {
                MaterialTheme(colorScheme = scheme) {
                    Box(Modifier.width(360.dp)) {
                        ModelResultCard(
                            title = "org/recommended",
                            author = "org",
                            metadata = "GGUF · 2 GB",
                            status = { Text("Recommended") },
                            onClick = {},
                            highlighted = true,
                        )
                    }
                }
            }
        }

        val pixels = onNodeWithTag("model-row:org/recommended")
            .captureToImage()
            .toPixelMap()
        onNodeWithTag("model-row:org/recommended").assertIsNotSelected()
        val middleY = pixels.height / 2
        var matchingLeadingPixels = 0
        for (x in 0 until minOf(12, pixels.width)) {
            if (colorsNear(railColor, pixels[x, middleY])) matchingLeadingPixels++ else break
        }
        assertEquals(3, matchingLeadingPixels, "Recommended rows must use exactly one 3dp signal rail")
        assertColorNear(
            selectedColor,
            pixels[(pixels.width / 2), middleY],
            "Recommended row interiors must use a tonal surface, not a gradient fill",
        )
    }

    @Test
    fun needsInformationRemainsAnExplicitNonColorState() = runComposeUiTest {
        setContent {
            AtDensityOne {
                MaterialTheme {
                    Box(Modifier.width(360.dp)) {
                        SearchModelListItem(
                            model = SearchModelsResponse.Model(id = "org/uncertain"),
                            onClick = {},
                        )
                    }
                }
            }
        }

        onNodeWithText("Needs information").assertIsDisplayed()
        onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                "Needs information. Compatibility has not been determined.",
            ),
        ).assertIsDisplayed()
        onNodeWithText("Incompatible").assertDoesNotExist()
    }
}

@Composable
private fun AtDensityOne(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalDensity provides Density(density = 1f, fontScale = 1f),
        content = content,
    )
}

private fun assertColorNear(expected: Color, actual: Color, message: String) {
    assertTrue(colorsNear(expected, actual), "$message. Expected $expected, found $actual")
}

private fun colorsNear(expected: Color, actual: Color, tolerance: Float = 0.02f): Boolean =
    abs(expected.red - actual.red) <= tolerance &&
        abs(expected.green - actual.green) <= tolerance &&
        abs(expected.blue - actual.blue) <= tolerance &&
        abs(expected.alpha - actual.alpha) <= tolerance
