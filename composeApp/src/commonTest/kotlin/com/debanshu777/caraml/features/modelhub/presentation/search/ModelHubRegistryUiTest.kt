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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasImeAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.debanshu777.caraml.core.platform.DeviceHints
import com.debanshu777.caraml.core.recommendation.PersonalizedRecommendation
import com.debanshu777.caraml.core.recommendation.RecommendationCategory
import com.debanshu777.caraml.core.recommendation.RecommendationProfile
import com.debanshu777.caraml.features.modelhub.domain.DescriptorState
import com.debanshu777.caraml.features.modelhub.domain.RecommendedModelUiState
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubBrowseControls
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubHeader
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubOverview
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubStateKind
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubStateView
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelListItem
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelResultCard
import com.debanshu777.caraml.features.modelhub.presentation.search.components.SearchBar
import com.debanshu777.caraml.features.modelhub.presentation.search.components.SearchModelListItem
import com.debanshu777.caraml.features.modelhub.presentation.search.components.selectedVariantLabel
import com.debanshu777.huggingfacemanager.model.ListModelsResponse
import com.debanshu777.huggingfacemanager.model.ModelSort
import com.debanshu777.huggingfacemanager.model.ParameterRange
import com.debanshu777.huggingfacemanager.model.SearchModelsResponse
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelHubRegistryUiTest {

    @Test
    fun assessedProductionRowKeepsReadableIdentityWidthWithStatusAndVariant() = runComposeUiTest {
        val repositoryId =
            "research-collective/a-very-long-model-name-that-must-remain-readable-on-phone"
        val model = ListModelsResponse.Model(
            author = "research-collective",
            downloads = 12_345,
            id = repositoryId,
            likes = 678,
            numParameters = 7_000_000_000L,
            pipelineTag = "Text generation",
        )
        setContent {
            AtDensityOne {
                MaterialTheme {
                    Box(Modifier.width(360.dp)) {
                        ModelListItem(
                            model = model,
                            onClick = {},
                            recommendationState = recommendedState(
                                model = model,
                                selectedVariantName = "model-Q4_K_M.gguf",
                            ),
                        )
                    }
                }
            }
        }

        val owner = onNodeWithText("research-collective", useUnmergedTree = true)
        val title = onNodeWithText(
            "a-very-long-model-name-that-must-remain-readable-on-phone",
            useUnmergedTree = true,
        )
        val metadata = onNodeWithText(
            "Text generation • 12.3K downloads • 7B params",
            useUnmergedTree = true,
        )
        owner.assertIsDisplayed()
        title.assertIsDisplayed()
        metadata.assertIsDisplayed()
        onNodeWithText("Recommended", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Selected variant: model-Q4_K_M.gguf", useUnmergedTree = true)
            .assertIsDisplayed()

        val titleWidth = title.fetchSemanticsNode().boundsInRoot.width
        val metadataWidth = metadata.fetchSemanticsNode().boundsInRoot.width
        assertTrue(
            titleWidth >= 180f && metadataWidth >= 180f,
            "Identity must retain at least half of a 360dp row; " +
                "titleWidth=$titleWidth metadataWidth=$metadataWidth",
        )
    }

    @Test
    fun mixedMetadataUsesBodyTypographyWhileOwnerStaysTechnical() =
        runComposeUiTest {
            setContent {
                AtDensityOne {
                    MaterialTheme {
                        Column(Modifier.width(360.dp)) {
                            ModelResultCard(
                                title = "iiiiiiii/narrow-metadata",
                                author = "iiiiiiii",
                                metadata = "iiiiiiii downloads",
                                status = {},
                                onClick = {},
                            )
                            ModelResultCard(
                                title = "WWWWWWWW/wide-metadata",
                                author = "WWWWWWWW",
                                metadata = "WWWWWWWW downloads",
                                status = {},
                                onClick = {},
                            )
                        }
                    }
                }
            }

            val narrowOwnerWidth = onNodeWithText("iiiiiiii", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot.width
            val wideOwnerWidth = onNodeWithText("WWWWWWWW", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot.width
            assertTrue(
                abs(narrowOwnerWidth - wideOwnerWidth) <= 1f,
                "Technical owner identifiers must remain monospace; " +
                    "narrow=$narrowOwnerWidth wide=$wideOwnerWidth",
            )

            val narrowMetadataLayout = mutableListOf<TextLayoutResult>()
            onNodeWithText("iiiiiiii downloads", useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                    action(narrowMetadataLayout)
                }
            val wideMetadataLayout = mutableListOf<TextLayoutResult>()
            onNodeWithText("WWWWWWWW downloads", useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                    action(wideMetadataLayout)
                }
            val narrowMetadataWidth = narrowMetadataLayout.single().getLineRight(0)
            val wideMetadataWidth = wideMetadataLayout.single().getLineRight(0)
            assertTrue(
                wideMetadataWidth >= narrowMetadataWidth + 12f,
                "Mixed prose metadata must use proportional body typography; " +
                    "narrow=$narrowMetadataWidth wide=$wideMetadataWidth",
            )
        }

    @Test
    fun selectedVariantLabelStylesOnlyArtifactTokenAsTechnical() {
        val label = selectedVariantLabel("model-Q4_K_M.gguf")

        assertEquals("Selected variant: model-Q4_K_M.gguf", label.text)
        assertEquals(
            1,
            label.spanStyles.size,
            "The artifact token needs one explicit technical span",
        )
        val technicalSpan = label.spanStyles.single()
        assertEquals(18, technicalSpan.start, "Body-styled prose must remain outside the span")
        assertEquals(label.length, technicalSpan.end)
        assertEquals(FontFamily.Monospace, technicalSpan.item.fontFamily)
    }

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
        val titleLayouts = mutableListOf<TextLayoutResult>()
        onNodeWithText("Models", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                assertTrue(action(titleLayouts))
            }
        val summaryLayouts = mutableListOf<TextLayoutResult>()
        onNodeWithText("24 models · 2 filters", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                assertTrue(action(summaryLayouts))
            }
        with(titleLayouts.single().layoutInput.style) {
            assertEquals(16.sp, fontSize)
            assertEquals(22.sp, lineHeight)
            assertEquals(FontWeight.SemiBold, fontWeight)
        }
        with(summaryLayouts.single().layoutInput.style) {
            assertEquals(14.sp, fontSize)
            assertEquals(20.sp, lineHeight)
        }
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
    fun contextStripUsesTheRouteCanvasInsteadOfOuterCardChrome() = runComposeUiTest {
        val canvas = Color.Magenta
        setContent {
            AtDensityOne {
                MaterialTheme {
                    Box(
                        Modifier
                            .width(360.dp)
                            .background(canvas),
                    ) {
                        ModelHubOverview(
                            storageInfo = StorageInfoUiState(
                                totalDeviceBytes = 8_589_934_592L,
                                availableDeviceBytes = 6_442_450_944L,
                                usedByModelsBytes = 2_147_483_648L,
                            ),
                            profile = null,
                            onOpenProfile = null,
                        )
                    }
                }
            }
        }

        val pixels = onNodeWithTag("model-context").captureToImage().toPixelMap()
        assertEquals(
            canvas,
            pixels[1, 1],
            "Secondary device context must sit directly on the route canvas",
        )
    }

    @Test
    fun compactContextKeepsStorageDeviceAndProfileVisibleWithoutHorizontalScrolling() =
        runComposeUiTest {
            setContent {
                AtDensityOne {
                    MaterialTheme {
                        Box(Modifier.width(360.dp)) {
                            ModelHubOverview(
                                storageInfo = StorageInfoUiState(
                                    totalDeviceBytes = 8_589_934_592L,
                                    availableDeviceBytes = 6_442_450_944L,
                                    usedByModelsBytes = 2_147_483_648L,
                                    deviceHints = DeviceHints(
                                        performanceCoreCount = 4,
                                        totalCoreCount = 8,
                                        memoryBudgetMB = 1_843,
                                        gpuBackendAvailable = true,
                                    ),
                                ),
                                profile = RecommendationProfile(),
                                onOpenProfile = {},
                            )
                        }
                    }
                }
            }

            val context = onNodeWithTag("model-context").fetchSemanticsNode()
            assertEquals(
                null,
                context.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange),
                "Storage, device, and profile context must wrap instead of restoring a clipped scroll",
            )
            onNodeWithText("Device profile").assertIsDisplayed().performClick()
            listOf("Storage", "Device", "Profile").forEach { label ->
                val bounds = onNodeWithText(label, useUnmergedTree = true)
                    .assertIsDisplayed()
                    .fetchSemanticsNode().boundsInRoot
                assertTrue(bounds.left >= context.boundsInRoot.left, "$label starts outside context")
                assertTrue(bounds.right <= context.boundsInRoot.right, "$label ends outside context")
            }
        }

    @Test
    fun browseToolbarKeepsEveryControlVisibleWithoutHorizontalScrollingAt360dp() =
        runComposeUiTest {
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

        val toolbar = onNodeWithTag("model-toolbar").assertIsDisplayed().fetchSemanticsNode()
        assertEquals(
            null,
            toolbar.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange),
            "Browse controls must not preserve a clipped horizontal scroll position",
        )
        listOf("Text", "Image", "Video", "Sort", "Filters").forEach { label ->
            val bounds = onNodeWithText(label, substring = true)
                .fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.left >= toolbar.boundsInRoot.left, "$label starts outside the toolbar")
            assertTrue(bounds.right <= toolbar.boundsInRoot.right, "$label ends outside the toolbar")
        }
        onNodeWithText("Minimum parameters").assertDoesNotExist()
        onNodeWithText("Maximum parameters").assertDoesNotExist()
    }

    @Test
    fun compactContextStartsAsOneDecisionSummaryAndRevealsTechnicalEvidenceOnDemand() =
        runComposeUiTest {
            setContent {
                AtDensityOne {
                    MaterialTheme {
                        Box(Modifier.width(360.dp)) {
                            ModelHubOverview(
                                storageInfo = StorageInfoUiState(
                                    totalDeviceBytes = 228L * 1024 * 1024 * 1024,
                                    availableDeviceBytes = 197L * 1024 * 1024 * 1024,
                                    usedByModelsBytes = 0L,
                                    deviceHints = DeviceHints(
                                        performanceCoreCount = 4,
                                        totalCoreCount = 8,
                                        memoryBudgetMB = 1_920,
                                        gpuBackendAvailable = true,
                                    ),
                                ),
                                profile = RecommendationProfile(),
                                onOpenProfile = {},
                            )
                        }
                    }
                }
            }

            onNodeWithText("Device profile").assertIsDisplayed()
            onNodeWithText("197 GB free · Balanced").assertIsDisplayed()
            listOf("Storage", "Device", "Profile").forEach { label ->
                onNodeWithText(label, useUnmergedTree = true).assertDoesNotExist()
            }

            onNodeWithText("Device profile").performClick()
            listOf("Storage", "Device", "Profile").forEach { label ->
                onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
            }
        }

    @Test
    fun oneLineModelRowUsesOneRoundedSurfaceWithoutReturningToOutlinedCardChrome() =
        runComposeUiTest {
        val pageColor = Color(0xFFF8FAFC)
        val rowColor = Color(0xFFD7E4E8)
        val scheme = lightColorScheme(
            surface = pageColor,
            surfaceContainerLow = rowColor,
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
        val roundedCorner = pixels[1, 1]
        val filledTopEdge = pixels[pixels.width / 2, 1]
        assertColorNear(pageColor, roundedCorner, "Rounded row corner must reveal the page canvas")
        assertTrue(
            filledTopEdge.registryColorDistance(roundedCorner) >= 0.05f,
            "The rounded registry row must own a quiet tonal surface",
        )
    }

    @Test
    fun compactModelMetadataWrapsWithoutEllipsisOrVisualOverflow() = runComposeUiTest {
        val metadata = "image-text-to-text · 27B · 2.2M downloads"
        setContent {
            AtDensityOne {
                MaterialTheme {
                    Box(Modifier.width(360.dp)) {
                        ModelResultCard(
                            title = "org/a-long-but-readable-model-name",
                            author = "org",
                            metadata = metadata,
                            status = { Text("Needs information") },
                            onClick = {},
                        )
                    }
                }
            }
        }

        val layouts = mutableListOf<TextLayoutResult>()
        onNodeWithText(metadata, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                assertTrue(action(layouts))
            }
        val layout = layouts.single()
        assertTrue(layout.lineCount <= 2, "Compact metadata should need at most two lines")
        assertTrue(
            !layout.hasVisualOverflow,
            "Compact metadata must not be ellipsized or clipped; " +
                "lines=${layout.lineCount} size=${layout.size} " +
                "width=${layout.didOverflowWidth} height=${layout.didOverflowHeight}",
        )
    }

    @Test
    fun compactProductionStatusRowGivesIdentityTheFullReadableWidth() = runComposeUiTest {
        setContent {
            AtDensityOne {
                MaterialTheme {
                    Box(Modifier.width(360.dp)) {
                        SearchModelListItem(
                            model = SearchModelsResponse.Model(
                                id = "org/compact-model",
                                trendingWeight = 42,
                            ),
                            onClick = {},
                        )
                    }
                }
            }
        }

        val row = onNodeWithTag("model-row:org/compact-model")
        val title = onNodeWithText("compact-model", useUnmergedTree = true)
        val metadata = onNodeWithText("Trending weight: 42", useUnmergedTree = true)
        val status = onNodeWithText("Needs information", useUnmergedTree = true)
        row.assertIsDisplayed()
        title.assertIsDisplayed()
        metadata.assertIsDisplayed()
        status.assertIsDisplayed()
        onNodeWithText("Selected variant:", substring = true).assertDoesNotExist()

        val rowBounds = row.fetchSemanticsNode().boundsInRoot
        val metadataBounds = metadata.fetchSemanticsNode().boundsInRoot
        val statusBounds = status.fetchSemanticsNode().boundsInRoot
        assertTrue(rowBounds.height <= 172f, "Simple status row was ${rowBounds.height}dp tall")
        assertTrue(
            statusBounds.top >= metadataBounds.bottom,
            "Compact status belongs below identity metadata; metadata=$metadataBounds status=$statusBounds",
        )
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
            titleBounds.width >= 280f,
            "Long names need the compact content width before status; width=${titleBounds.width}",
        )
        assertTrue(
            statusBounds.top >= metadata.fetchSemanticsNode().boundsInRoot.bottom,
            "Long-name status must follow metadata without competing for title width",
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
        val rowSemantics = onNodeWithTag("model-row:org/recommended")
            .fetchSemanticsNode()
            .config
        assertEquals(Role.Button, rowSemantics[SemanticsProperties.Role])
        assertEquals(null, rowSemantics.getOrNull(SemanticsProperties.Selected))
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

private fun Color.registryColorDistance(other: Color): Float =
    abs(red - other.red) + abs(green - other.green) + abs(blue - other.blue)

private fun colorsNear(expected: Color, actual: Color, tolerance: Float = 0.02f): Boolean =
    abs(expected.red - actual.red) <= tolerance &&
        abs(expected.green - actual.green) <= tolerance &&
        abs(expected.blue - actual.blue) <= tolerance &&
        abs(expected.alpha - actual.alpha) <= tolerance

private fun androidx.compose.ui.geometry.Rect.expandToInclude(
    other: androidx.compose.ui.geometry.Rect,
): androidx.compose.ui.geometry.Rect = androidx.compose.ui.geometry.Rect(
    left = minOf(left, other.left),
    top = minOf(top, other.top),
    right = maxOf(right, other.right),
    bottom = maxOf(bottom, other.bottom),
)

private fun recommendedState(
    model: ListModelsResponse.Model,
    selectedVariantName: String,
): RecommendedModelUiState = RecommendedModelUiState(
    sourceModel = model,
    repositoryId = model.id,
    descriptorState = DescriptorState.ASSESSED,
    objectiveAssessment = null,
    personalizedResult = PersonalizedRecommendation(
        assessmentKey = "test-assessment",
        category = RecommendationCategory.RECOMMENDED,
        selectedPlan = null,
        reasons = emptyList(),
        profile = RecommendationProfile(),
    ),
    selectedVariantName = selectedVariantName,
    stableModelId = requireNotNull(model.id),
    sourceIndex = 0,
)
