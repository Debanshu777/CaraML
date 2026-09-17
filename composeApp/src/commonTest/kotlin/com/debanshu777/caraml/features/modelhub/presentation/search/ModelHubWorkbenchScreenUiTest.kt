@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.features.modelhub.presentation.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubContextStrip
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubToolbar
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelResultCard
import com.debanshu777.caraml.features.modelhub.presentation.search.components.SearchBar
import com.debanshu777.huggingfacemanager.model.ModelSort
import com.debanshu777.huggingfacemanager.model.ParameterRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelHubWorkbenchScreenUiTest {

    @Test
    fun compactModelsPlacesCommandBeforeContextAndResultsInFirstViewport() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                MaterialTheme {
                    Box(Modifier.requiredSize(width = 360.dp, height = 800.dp)) {
                        WorkbenchFixture(
                            modifier = Modifier.fillMaxSize(),
                            result = FixtureResult.Content,
                        )
                    }
                }
            }
        }

        val header = onNodeWithText("Models").fetchSemanticsNode().boundsInRoot
        val tabs = onNodeWithText("Discover").fetchSemanticsNode().boundsInRoot
        val command = onNodeWithTag("model-command").fetchSemanticsNode().boundsInRoot
        val context = onNodeWithTag("model-context").fetchSemanticsNode().boundsInRoot
        val toolbar = onNodeWithTag("model-toolbar").fetchSemanticsNode().boundsInRoot
        val summary = onNodeWithTag("model-summary").fetchSemanticsNode().boundsInRoot
        val results = onNodeWithTag("model-results").fetchSemanticsNode().boundsInRoot

        assertTrue(header.top < tabs.top)
        assertTrue(tabs.top < command.top)
        assertTrue(command.top < context.top)
        assertTrue(context.top < toolbar.top)
        assertTrue(toolbar.top < summary.top)
        assertTrue(summary.top < results.top)
        onNodeWithTag("model-command").assertIsDisplayed()
        assertTrue(
            summary.top < 800f || results.top < 800f,
            "The result summary or first result must begin in the first 360x800dp viewport",
        )
    }

    @Test
    fun discoverTabHasExactlyOneVerticalScrollOwner() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(width = 360.dp, height = 480.dp)) {
                    WorkbenchFixture(modifier = Modifier.fillMaxSize())
                }
            }
        }

        assertEquals(1, verticalScrollOwnerCount())
    }

    @Test
    fun libraryTabHasExactlyOneVerticalScrollOwner() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(width = 360.dp, height = 480.dp)) {
                    WorkbenchFixture(
                        modifier = Modifier.fillMaxSize(),
                        selectedTabIndex = 1,
                    )
                }
            }
        }

        assertEquals(1, verticalScrollOwnerCount())
        onNodeWithText("Library").assertIsDisplayed()
    }

    @Test
    fun filteredEmptyStateShowsCountFilterSummaryAndReset() = runComposeUiTest {
        var resets = 0
        setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(width = 360.dp, height = 480.dp)) {
                    WorkbenchFixture(
                        modifier = Modifier.fillMaxSize(),
                        result = FixtureResult.Empty,
                        activeFilterCount = 2,
                        onResetFilters = { resets += 1 },
                    )
                }
            }
        }

        onNodeWithText("0 models").assertIsDisplayed()
        onNodeWithText("2 filters active").assertIsDisplayed()
        onNodeWithText("No models match the active filters.")
            .performScrollTo()
            .assertIsDisplayed()
        onNodeWithText("Reset filters").performScrollTo().performClick()
        runOnIdle { assertEquals(1, resets) }
    }

    @Test
    fun loadingEmptyErrorAndContentHaveDistinctSemantics() = runComposeUiTest {
        var result by mutableStateOf(FixtureResult.Loading)
        setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(width = 360.dp, height = 480.dp)) {
                    WorkbenchFixture(
                        modifier = Modifier.fillMaxSize(),
                        result = result,
                    )
                }
            }
        }

        assertResultState("Loading model results")
        runOnIdle { result = FixtureResult.Empty }
        assertResultState("No model results")
        runOnIdle { result = FixtureResult.Error }
        assertResultState("Model results failed")
        runOnIdle { result = FixtureResult.Content }
        assertResultState("Model results loaded")
    }

    @Test
    fun width840UsesSupportingContextWithoutShrinkingResultsBelow480Dp() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                MaterialTheme {
                    Box(Modifier.requiredSize(width = 840.dp, height = 480.dp)) {
                        FixtureTab(
                            modifier = Modifier.fillMaxSize(),
                            windowWidth = 840.dp,
                        )
                    }
                }
            }
        }

        val supporting = onNodeWithTag("model-supporting-context")
            .fetchSemanticsNode().boundsInRoot
        val results = onNodeWithTag("model-primary-results")
            .fetchSemanticsNode().boundsInRoot

        assertTrue(supporting.width in 280f..320f, "Supporting width was ${supporting.width}dp")
        assertTrue(results.width >= 480f, "Results width was ${results.width}dp")
        assertTrue(supporting.left > results.left)
    }

    @Test
    fun compactTwoHundredPercentTextKeepsResetAndFirstResultReachable() = runComposeUiTest {
        var resets = 0
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                MaterialTheme {
                    Box(Modifier.requiredSize(width = 360.dp, height = 420.dp)) {
                        FixtureTab(
                            modifier = Modifier.fillMaxSize(),
                            activeFilterCount = 2,
                            onResetFilters = { resets += 1 },
                        )
                    }
                }
            }
        }

        assertEquals(1, verticalScrollOwnerCount())
        onNodeWithText("Reset filters").performScrollTo().assertIsDisplayed().performClick()
        onNodeWithText("long-model-name-that-remains-readable")
            .performScrollTo()
            .assertIsDisplayed()
        runOnIdle { assertEquals(1, resets) }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.verticalScrollOwnerCount(): Int =
        onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
        ).fetchSemanticsNodes().size

    private fun androidx.compose.ui.test.ComposeUiTest.assertResultState(description: String) {
        onNodeWithTag("model-results").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, description),
        )
    }
}

private enum class FixtureResult {
    Loading,
    Empty,
    Error,
    Content,
}

@Composable
private fun WorkbenchFixture(
    modifier: Modifier = Modifier,
    selectedTabIndex: Int = 0,
    result: FixtureResult = FixtureResult.Content,
    activeFilterCount: Int = 0,
    onResetFilters: () -> Unit = {},
) {
    val motion = com.debanshu777.caraml.core.ui.motion.LocalAuroraMotionPolicy.current
    ModelHubScreenLayout(
        selectedTabIndex = selectedTabIndex,
        onTabSelected = {},
        modifier = modifier,
        discoverContent = {
            FixtureTab(
                result = result,
                activeFilterCount = activeFilterCount,
                onResetFilters = onResetFilters,
            )
        },
        libraryContent = {
            ModelHubTabLayout(
                context = { FixtureContext() },
                toolbar = { Text("Library readiness") },
                summary = {
                    ModelHubResultSummary(
                        resultCount = 0,
                        resultNoun = "downloaded models",
                    )
                },
                results = {
                    modelHubResultItems(
                        isLoading = false,
                        hasResponse = true,
                        errorMessage = null,
                        models = emptyList<String>(),
                        itemKey = { it },
                        blockingLoadingKey = "library-loading",
                        refreshLoadingKey = "library-refreshing",
                        errorKey = "library-error",
                        emptyKey = "library-empty",
                        blockingLoadingDescription = "Loading downloaded models",
                        refreshLoadingDescription = "Refreshing downloaded models",
                        emptyMessage = "No downloaded models yet.",
                        motion = motion,
                    ) { model, itemModifier -> Text(model, modifier = itemModifier) }
                },
            )
        },
    )
}

@Composable
private fun FixtureTab(
    modifier: Modifier = Modifier,
    windowWidth: androidx.compose.ui.unit.Dp? = null,
    result: FixtureResult = FixtureResult.Content,
    activeFilterCount: Int = 0,
    onResetFilters: () -> Unit = {},
) {
    val motion = com.debanshu777.caraml.core.ui.motion.LocalAuroraMotionPolicy.current
    val models = if (result == FixtureResult.Content) {
        listOf("org/long-model-name-that-remains-readable", "org/second-model")
    } else {
        emptyList()
    }
    ModelHubTabLayout(
        modifier = modifier,
        windowWidth = windowWidth,
        command = {
            SearchBar(
                query = "",
                onQueryChange = {},
                onSearch = {},
            )
        },
        context = { FixtureContext() },
        toolbar = {
            ModelHubToolbar(
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
        },
        summary = {
            ModelHubResultSummary(
                resultCount = models.size,
                activeFilterCount = activeFilterCount,
                onResetFilters = onResetFilters,
            )
        },
        results = {
            modelHubResultItems(
                isLoading = result == FixtureResult.Loading,
                hasResponse = result != FixtureResult.Loading,
                errorMessage = if (result == FixtureResult.Error) "Models could not be loaded." else null,
                models = models,
                itemKey = { it },
                blockingLoadingKey = "fixture-loading",
                refreshLoadingKey = "fixture-refreshing",
                errorKey = "fixture-error",
                emptyKey = "fixture-empty",
                blockingLoadingDescription = "Loading model results",
                refreshLoadingDescription = "Refreshing model results",
                emptyMessage = if (activeFilterCount > 0) {
                    "No models match the active filters."
                } else {
                    "No models are available yet."
                },
                motion = motion,
            ) { model, itemModifier ->
                ModelResultCard(
                    title = model,
                    author = "org",
                    metadata = "Text generation",
                    status = {},
                    onClick = {},
                    modifier = itemModifier,
                )
            }
        },
    )
}

@Composable
private fun FixtureContext() {
    ModelHubContextStrip(
        storageInfo = StorageInfoUiState(
            totalDeviceBytes = 8_589_934_592L,
            availableDeviceBytes = 6_442_450_944L,
            usedByModelsBytes = 2_147_483_648L,
        ),
        profile = null,
        onOpenProfile = null,
    )
}
