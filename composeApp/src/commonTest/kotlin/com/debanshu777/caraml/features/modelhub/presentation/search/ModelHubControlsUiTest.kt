@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.features.modelhub.presentation.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasImeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.AppShapes
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubBrowseControls
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubToolbar
import com.debanshu777.caraml.features.modelhub.presentation.search.components.SearchBar
import com.debanshu777.caraml.features.modelhub.presentation.search.components.SortFilterChips
import com.debanshu777.huggingfacemanager.model.ModelSort
import com.debanshu777.huggingfacemanager.model.ParameterRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelHubControlsUiTest {

    @Test
    fun modelKindsAreOneRadioGroupWithOneActionAndVisibleSelection() = runComposeUiTest {
        var selectedMode by mutableStateOf(ModelHubBrowseMode.LanguageModels)
        val selectedModes = mutableListOf<ModelHubBrowseMode>()
        setContent {
            MaterialTheme(shapes = AppShapes) {
                Box(Modifier.width(360.dp)) {
                    ModelHubToolbar(
                        browseMode = selectedMode,
                        onBrowseModeChange = {
                            selectedModes += it
                            selectedMode = it
                        },
                        showSortFilters = false,
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

        onNodeWithTag("model-kind-group", useUnmergedTree = true).assert(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup),
        )
        listOf(
            "Text" to true,
            "Image" to false,
            "Video" to false,
        ).forEach { (label, selected) ->
            val option = onNode(
                hasText(label) and isSelectable(),
            ).fetchSemanticsNode()
            assertEquals(Role.RadioButton, option.config[SemanticsProperties.Role])
            assertEquals(selected, option.config[SemanticsProperties.Selected])
            assertEquals(AppShapes.small, option.config[SemanticsProperties.Shape])
            val actionCount = onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().count { actionNode ->
                option.boundsInRoot.contains(actionNode.boundsInRoot.center)
            }
            assertEquals(1, actionCount, "$label must expose exactly one click action")
        }
        onNodeWithTag("Selected model kind Text", useUnmergedTree = true).assertIsDisplayed()

        onNode(hasText("Image") and isSelectable()).performClick()

        runOnIdle {
            assertEquals(listOf(ModelHubBrowseMode.DiffusionImage), selectedModes)
        }
        onNode(hasText("Text") and isSelectable()).assertIsNotSelected()
        onNode(hasText("Image") and isSelectable()).assertIsSelected()
        onNodeWithTag("Selected model kind Image", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun legacyPositionalToolbarSignaturesRemainCallable() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Column {
                    SortFilterChips(
                        ModelOrdering.Server(ModelSort.TRENDING),
                        ModelSort.TRENDING,
                        ParameterRange.ZERO,
                        ParameterRange.SIX_B,
                        {},
                        {},
                        {},
                        {},
                        Modifier.testTag("legacy-sort-filter"),
                    )
                    ModelHubBrowseControls(
                        ModelHubBrowseMode.LanguageModels,
                        {},
                        true,
                        ModelOrdering.Server(ModelSort.TRENDING),
                        ModelSort.TRENDING,
                        ParameterRange.ZERO,
                        ParameterRange.SIX_B,
                        {},
                        {},
                        {},
                        {},
                        Modifier.testTag("legacy-browse-controls"),
                    )
                    ModelHubToolbar(
                        ModelHubBrowseMode.LanguageModels,
                        {},
                        true,
                        ModelOrdering.Server(ModelSort.TRENDING),
                        ModelSort.TRENDING,
                        ParameterRange.ZERO,
                        ParameterRange.SIX_B,
                        {},
                        {},
                        {},
                        {},
                        Modifier.testTag("legacy-toolbar"),
                    )
                }
            }
        }

        onNodeWithTag("legacy-sort-filter").assertExists()
        onNodeWithTag("legacy-browse-controls").assertExists()
        onNodeWithTag("legacy-toolbar").assertExists()
    }

    @Test
    fun compactBrowseControlsUseOneBandAndPutRangesBehindFilters() = runComposeUiTest {
        val controlWidth = 320.dp
        var applyCalls = 0
        setContent {
            MaterialTheme(shapes = AppShapes) {
                Box(Modifier.width(controlWidth)) {
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
                        onFiltersApplied = { applyCalls += 1 },
                    )
                }
            }
        }

        val kindPositions = listOf("Text", "Image", "Video", "Sort", "Filters").map {
            onNodeWithText(it).fetchSemanticsNode().positionInRoot.y
        }
        assertTrue(kindPositions.max() - kindPositions.min() < 1f)
        listOf("Sort models", "Filters").forEach { description ->
            val control = onNodeWithContentDescription(description).fetchSemanticsNode()
            assertEquals(AppShapes.small, control.config[SemanticsProperties.Shape])
        }
        onNodeWithText("Min: 0").assertDoesNotExist()
        onNodeWithText("Max: 6B").assertDoesNotExist()

        onNodeWithText("Filters").performScrollTo().performClick()
        onNodeWithText("Minimum parameters").assertIsDisplayed()
        onNodeWithText("Maximum parameters")
            .performScrollTo()
            .assertIsDisplayed()
        onNodeWithText("Done").performScrollTo().performClick()
        runOnIdle { assertEquals(1, applyCalls) }
    }

    @Test
    fun filterSheetStagesSelectionUntilDone() = runComposeUiTest {
        var selectedSort by mutableStateOf(ModelSort.TRENDING)
        var applyCalls = 0
        setContent {
            MaterialTheme(shapes = AppShapes) {
                SortFilterChips(
                    ordering = ModelOrdering.Server(selectedSort),
                    sort = selectedSort,
                    minParams = ParameterRange.ZERO,
                    maxParams = ParameterRange.SIX_B,
                    onSortChange = { selectedSort = it },
                    onOrderingChange = {},
                    onMinParamsChange = {},
                    onMaxParamsChange = {},
                    onFiltersApplied = { applyCalls += 1 },
                )
            }
        }

        onNodeWithText("Filters").performClick()
        onNode(hasText("Trending") and isSelectable())
            .assertIsSelected()
            .fetchSemanticsNode()
            .also { option ->
                assertEquals(AppShapes.small, option.config[SemanticsProperties.Shape])
            }
        onNodeWithText("Downloads").performClick()

        runOnIdle { assertEquals(ModelSort.TRENDING, selectedSort) }
        onNode(hasText("Downloads") and isSelectable()).assertIsSelected()
        onNodeWithText("Done").performScrollTo().performClick()
        runOnIdle {
            assertEquals(ModelSort.DOWNLOADS, selectedSort)
            assertEquals(1, applyCalls)
        }
        onNodeWithText("Filters (1)").assertIsDisplayed()
    }

    @Test
    fun searchSubmissionUsesTheFieldImeActionWithoutADuplicateButton() = runComposeUiTest {
        var submissions = 0
        setContent {
            MaterialTheme {
                SearchBar(
                    query = "tinyllama",
                    onQueryChange = {},
                    onSearch = { submissions += 1 },
                )
            }
        }

        onNode(hasImeAction(androidx.compose.ui.text.input.ImeAction.Search)).performImeAction()
        runOnIdle { assertEquals(1, submissions) }
        onAllNodesWithContentDescription("Submit model search").assertCountEquals(0)
    }

    @Test
    fun emptySearchDoesNotShowADuplicateTrailingSearchAction() = runComposeUiTest {
        var query by mutableStateOf("")
        setContent {
            MaterialTheme {
                SearchBar(
                    query = query,
                    onQueryChange = { query = it },
                    onSearch = {},
                )
            }
        }

        onAllNodesWithContentDescription("Submit model search").assertCountEquals(0)
        runOnIdle { query = "tinyllama" }
        onAllNodesWithContentDescription("Submit model search").assertCountEquals(0)
        onNodeWithContentDescription("Clear model search").assertIsDisplayed()
    }

    @Test
    fun clearSearchActionCanClearBothTheQueryAndItsResultState() = runComposeUiTest {
        var clearCalls = 0
        setContent {
            MaterialTheme {
                SearchBar(
                    query = "MiniCPM5",
                    onQueryChange = {},
                    onSearch = {},
                    onClear = { clearCalls += 1 },
                )
            }
        }

        onNodeWithContentDescription("Clear model search").performClick()
        runOnIdle { assertEquals(1, clearCalls) }
    }

    @Test
    fun whitespaceSearchStillExposesClearAction() = runComposeUiTest {
        var query by mutableStateOf("   ")
        setContent {
            MaterialTheme {
                SearchBar(
                    query = query,
                    onQueryChange = { query = it },
                    onSearch = {},
                )
            }
        }

        onNodeWithContentDescription("Clear model search").performClick()
        runOnIdle { assertEquals("", query) }
    }
}
