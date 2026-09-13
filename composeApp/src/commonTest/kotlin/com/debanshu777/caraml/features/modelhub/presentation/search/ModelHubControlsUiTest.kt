@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.features.modelhub.presentation.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubBrowseControls
import com.debanshu777.caraml.features.modelhub.presentation.search.components.SearchBar
import com.debanshu777.caraml.features.modelhub.presentation.search.components.SortFilterChips
import com.debanshu777.huggingfacemanager.model.ModelSort
import com.debanshu777.huggingfacemanager.model.ParameterRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelHubControlsUiTest {

    @Test
    fun compactBrowseControlsKeepKindsOnOneRowAndPutRangesBehindFilters() = runComposeUiTest {
        val controlWidth = 320.dp
        setContent {
            MaterialTheme {
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
                    )
                }
            }
        }

        val kindPositions = listOf("LLM", "Image", "Video").map {
            onNodeWithText(it).fetchSemanticsNode().positionInRoot.y
        }
        assertTrue(kindPositions.max() - kindPositions.min() < 1f)
        onNodeWithText("Server order").assertIsDisplayed()
        onNodeWithText("Filters").assertIsDisplayed()
        onNodeWithText("Min: 0").assertDoesNotExist()
        onNodeWithText("Max: 6B").assertDoesNotExist()

        onNodeWithText("Filters").performClick()
        onNodeWithText("Minimum parameters").assertIsDisplayed()
        onNodeWithText("Maximum parameters")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun sortSheetExposesSelectionAndDismissesAfterChoice() = runComposeUiTest {
        var selectedSort by mutableStateOf(ModelSort.TRENDING)
        setContent {
            MaterialTheme {
                SortFilterChips(
                    ordering = ModelOrdering.Server(selectedSort),
                    sort = selectedSort,
                    minParams = ParameterRange.ZERO,
                    maxParams = ParameterRange.SIX_B,
                    onSortChange = { selectedSort = it },
                    onOrderingChange = {},
                    onMinParamsChange = {},
                    onMaxParamsChange = {},
                )
            }
        }

        onNodeWithText("Filters").performClick()
        onNode(hasText("Trending") and isSelectable()).assertIsSelected()
        onNodeWithText("Downloads").performClick()

        runOnIdle { assertEquals(ModelSort.DOWNLOADS, selectedSort) }
        onNode(hasText("Downloads") and isSelectable()).assertIsSelected()
        onNodeWithText("Done").performClick()
        onNodeWithText("Filters (1)").assertIsDisplayed()
    }

    @Test
    fun searchSubmissionIsAnAccessibleFieldAction() = runComposeUiTest {
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

        onNodeWithContentDescription("Submit model search").performClick()
        runOnIdle { assertEquals(1, submissions) }
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
        onAllNodesWithContentDescription("Submit model search").assertCountEquals(1)
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
}
