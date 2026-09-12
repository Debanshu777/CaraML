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
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
    fun browseControlsWrapTogetherOnCompactAndShareOneExpandedRow() = runComposeUiTest {
        var controlWidth by mutableStateOf(320.dp)
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

        onNodeWithText("LLM").assertIsDisplayed()
        onNodeWithText("Max: 6B").assertIsDisplayed()
        val compactKindY = onNodeWithText("LLM").fetchSemanticsNode().positionInRoot.y
        val compactMaxY = onNodeWithText("Max: 6B").fetchSemanticsNode().positionInRoot.y
        assertTrue(compactMaxY > compactKindY, "Compact controls must wrap within one container")

        runOnIdle { controlWidth = 720.dp }
        waitForIdle()

        val mediumKindY = onNodeWithText("LLM").fetchSemanticsNode().positionInRoot.y
        val mediumMaxY = onNodeWithText("Max: 6B").fetchSemanticsNode().positionInRoot.y
        assertTrue(
            kotlin.math.abs(mediumMaxY - mediumKindY) < 1f,
            "Medium controls must remain on one row",
        )

        runOnIdle { controlWidth = 1_000.dp }
        waitForIdle()

        val expandedKindY = onNodeWithText("LLM").fetchSemanticsNode().positionInRoot.y
        val expandedMaxY = onNodeWithText("Max: 6B").fetchSemanticsNode().positionInRoot.y
        assertTrue(
            kotlin.math.abs(expandedMaxY - expandedKindY) < 1f,
            "Expanded controls must remain on one row",
        )
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

        onNodeWithText("Sort: Trending").performClick()
        onNode(hasText("Trending") and isSelectable()).assertIsSelected()
        onNodeWithText("Downloads").performClick()

        runOnIdle { assertEquals(ModelSort.DOWNLOADS, selectedSort) }
        onNodeWithText("Downloads").assertDoesNotExist()
        onNodeWithText("Sort: Downloads").assertIsDisplayed()
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
}
