@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.features.modelhub.presentation.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasImeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubFilterPanel
import com.debanshu777.caraml.features.modelhub.presentation.search.components.SearchBar
import com.debanshu777.caraml.sdk.discovery.huggingface.model.ModelSort
import com.debanshu777.caraml.sdk.discovery.huggingface.model.ParameterRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelHubControlsUiTest {

    @Test
    fun filterPanelStagesAccessibleModelKindUntilApply() = runComposeUiTest {
        val applied = mutableListOf<ModelHubBrowseMode>()
        setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp)) {
                    ModelHubFilterPanel(ModelHubBrowseMode.LanguageModels,
                        ModelOrdering.Server(ModelSort.TRENDING), ParameterRange.ZERO, ParameterRange.SIX_B,
                    ) { mode, _, _, _, _ -> applied += mode }
                }
            }
        }
        listOf("Text", "Image", "Video").forEach { label ->
            val option = onNode(hasText(label) and isSelectable()).fetchSemanticsNode()
            assertEquals(Role.RadioButton, option.config[SemanticsProperties.Role])
            val actionCount = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick),
                useUnmergedTree = true).fetchSemanticsNodes().count {
                option.boundsInRoot.contains(it.boundsInRoot.center)
            }
            assertEquals(1, actionCount, "$label must expose one click action")
        }
        onNode(hasText("Text") and isSelectable()).assertIsSelected()
        onNode(hasText("Image") and isSelectable()).performClick().assertIsSelected()
        onNode(hasText("Text") and isSelectable()).assertIsNotSelected()
        runOnIdle { assertTrue(applied.isEmpty()) }
        onNodeWithText("View models").performClick()
        runOnIdle { assertEquals(listOf(ModelHubBrowseMode.DiffusionImage), applied) }
    }

    @Test
    fun filterPanelResetStagesAllDefaultsUntilApply() = runComposeUiTest {
        var applied: List<Any>? = null
        setContent {
            MaterialTheme {
                Box(Modifier.width(360.dp)) {
                    ModelHubFilterPanel(ModelHubBrowseMode.DiffusionVideo, ModelOrdering.Personalized,
                        ParameterRange.THREE_B, ParameterRange.SIX_B, "onnx",
                    ) { mode, ordering, min, max, format -> applied = listOf(mode, ordering, min, max, format) }
                }
            }
        }
        onNodeWithText("Reset").performClick()
        onNode(hasText("Text") and isSelectable()).assertIsSelected()
        onNode(hasText("GGUF") and isSelectable()).assertIsSelected()
        runOnIdle { assertEquals(null, applied) }
        onNodeWithText("View models").performClick()
        runOnIdle { assertEquals(listOf(ModelHubBrowseMode.LanguageModels,
            ModelOrdering.Server(ModelSort.TRENDING), ParameterRange.ZERO, ParameterRange.SIX_B, "gguf"), applied) }
    }

    @Test
    fun filterPanelOffersVerifiedServerOrdersAndStagesLoadedBestFit() = runComposeUiTest {
        var selected: ModelOrdering = ModelOrdering.Server(ModelSort.TRENDING)
        setContent {
            MaterialTheme {
                Box(Modifier.width(360.dp)) {
                    ModelHubFilterPanel(ModelHubBrowseMode.LanguageModels, selected,
                        ParameterRange.ZERO, ParameterRange.SIX_B,
                    ) { _, ordering, _, _, _ -> selected = ordering }
                }
            }
        }
        onNodeWithText("Sort").performClick()
        listOf("Trending", "Most downloads", "Most liked", "Newest", "Recently updated",
            "Best fit among loaded").forEach { onNodeWithText(it).performScrollTo().assertIsDisplayed() }
        listOf("Most params", "Least params", "Similar").forEach { onNodeWithText(it).assertDoesNotExist() }
        onNodeWithText("Best fit among loaded").performClick()
        runOnIdle { assertEquals(ModelOrdering.Server(ModelSort.TRENDING), selected) }
        onNodeWithText("View models").performClick()
        runOnIdle { assertEquals(ModelOrdering.Personalized, selected) }
    }


    @Test
    fun searchSubmissionUsesImeWithoutDuplicateTrailingButton() = runComposeUiTest {
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
    fun searchClearActionAppearsOnlyForNonemptyDraft() = runComposeUiTest {
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
    fun invalidSearchShowsAccessibleInputFeedback() = runComposeUiTest {
        setContent {
            MaterialTheme {
                SearchBar(
                    query = "invalid",
                    onQueryChange = {},
                    onSearch = {},
                    errorMessage = "Search text is too long or contains invalid characters.",
                )
            }
        }
        onNodeWithText("Search text is too long or contains invalid characters.").assertIsDisplayed()
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

    @Test
    fun pageErrorRetainsRetryAndLoadedCount() = runComposeUiTest {
        var retries = 0
        setContent {
            MaterialTheme {
                ModelPageFooter(
                    loadedCount = 20,
                    loading = false,
                    error = "Connection lost",
                    hasMore = true,
                    sessionLimitReached = false,
                    onLoadMore = { retries++ },
                )
            }
        }
        onNodeWithText("Couldn't load more").assertIsDisplayed()
        onNodeWithText("Your loaded models are still here. Connection lost").assertIsDisplayed()
        onNodeWithText("Retry load more").performClick()
        runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun stoppedPaginationOffersStartOverInsteadOfDeadRetry() = runComposeUiTest {
        var restarts = 0
        setContent {
            MaterialTheme {
                ModelPageFooter(
                    loadedCount = 40,
                    loading = false,
                    error = "Pagination stopped",
                    hasMore = false,
                    sessionLimitReached = false,
                    onLoadMore = {},
                    onStartOver = { restarts++ },
                )
            }
        }
        onNodeWithText("Refine your search above to explore more models.").assertIsDisplayed()
        onNodeWithText("Retry load more").assertDoesNotExist()
        onNodeWithText("Start over").performClick()
        runOnIdle { assertEquals(1, restarts) }
    }
}
