@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.features.modelhub.presentation.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.features.modelhub.presentation.search.components.SearchBar
import com.debanshu777.caraml.features.modelhub.presentation.search.components.SortFilterChips
import com.debanshu777.huggingfacemanager.model.ModelSort
import com.debanshu777.huggingfacemanager.model.ParameterRange
import kotlin.test.Test
import kotlin.test.assertEquals

class ModelHubControlsUiTest {

    @Test
    fun everyFilterRemainsReachableAtCompactWidth() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Box(Modifier.width(240.dp)) {
                    SortFilterChips(
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

        onNode(hasScrollToIndexAction()).performScrollToIndex(3)
        onNodeWithText("Max: 6B").assertIsDisplayed()
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
