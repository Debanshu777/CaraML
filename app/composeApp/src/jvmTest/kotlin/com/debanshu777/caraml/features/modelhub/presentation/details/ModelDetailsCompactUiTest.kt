@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.features.modelhub.presentation.details

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.requiredSize
import com.debanshu777.caraml.core.ui.components.FrostedPageScaffold
import com.debanshu777.caraml.core.ui.layout.AppContentKind
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubBackHeader
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.assertIsSelected
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.math.abs
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.core.theme.CaraMLTheme
import com.debanshu777.caraml.core.theme.ThemeMode
import com.debanshu777.caraml.core.theme.ThemePreferences
import com.debanshu777.caraml.features.modelhub.presentation.details.components.ModelDetailContent
import com.debanshu777.caraml.features.modelhub.presentation.search.GgufFileUiState
import com.debanshu777.caraml.features.modelhub.presentation.search.saveModelHubEvidence
import com.debanshu777.caraml.sdk.discovery.huggingface.model.ModelDetailResponse
import kotlin.test.Test

class ModelDetailsCompactUiTest {
    @Test
    fun publisherExcerptAndFactsRemainReachableAcrossSizesAndThemes() = runComposeUiTest {
        var width by mutableStateOf(390.dp)
        var fontScale by mutableStateOf(1f)
        var theme by mutableStateOf(ThemeMode.LIGHT)
        val model = ModelDetailResponse(
            id = "sample/Compact-Chat-GGUF", sha = "a".repeat(40), downloads = 120000, likes = 420,
            pipelineTag = "text-generation", lastModified = "2026-11-01T00:00:00Z",
            gguf = ModelDetailResponse.Gguf(total = 600000000, architecture = "qwen3", contextLength = 40960),
            cardData = ModelDetailResponse.CardData(license = "apache-2.0", language = listOf("en", "hi"), baseModel = listOf("sample/Compact-Chat")),
        )
        val description = "A compact multilingual model for local conversation and instruction following. " +
            "This publisher excerpt explains the intended uses and limitations of the model. " +
            "Read the complete model card for training information, evaluation methodology, and usage examples."
        var openedUri: String? = null
        val uriHandler = object : UriHandler {
            override fun openUri(uri: String) { openedUri = uri }
        }
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale), LocalUriHandler provides uriHandler) {
                CaraMLTheme(ThemePreferences(themeMode = theme, reduceMotion = true)) {
                    Box(Modifier.requiredSize(width, 780.dp).testTag("visual-root")) {
                        FrostedPageScaffold(
                            kind = AppContentKind.Details, safeInsets = WindowInsets(0, 0, 0, 0),
                            header = { ModelHubBackHeader(onBack = {}, label = null) },
                        ) { insets ->
                            ModelDetailContent(
                                model = model,
                                modelCardExcerpt = description,
                                ggufFiles = listOf("Q4_K_M", "Q8_0").map {
                                    GgufFileUiState(
                                        "Compact-Chat-$it.gguf", "Compact-Chat-$it.gguf", 500000000, false, null,
                                        artifact = com.debanshu777.caraml.core.download.transfer.DownloadArtifactIdentity.create(
                                            repositoryId = "sample/Compact-Chat-GGUF", immutableRevision = "a".repeat(40),
                                            relativePath = "Compact-Chat-$it.gguf", remoteObjectId = null, expectedBytes = 500000000,
                                        ),
                                    )
                                },
                                isDownloading = false, onDownloadClick = { _, _, _ -> },
                                contentPadding = PaddingValues(
                                    top = insets.calculateTopPadding() + 12.dp,
                                    bottom = 16.dp,
                                ),
                            )
                        }
                    }
                }
            }
        }
        onNodeWithText("Details").assertDoesNotExist()
        onNodeWithText("From model card ↗").assertDoesNotExist()
        onNodeWithText("Read more").assertDoesNotExist()
        onNodeWithText("Less").assertDoesNotExist()
        val layouts = mutableListOf<TextLayoutResult>()
        onNodeWithTag("detail-description").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse(layouts.single().hasVisualOverflow, "The publisher excerpt is fully expanded")
        layouts.clear()
        onNodeWithTag("detail-title").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val titleLayout = layouts.single()
        onNodeWithTag("detail-title").performTouchInput { click(titleLayout.getBoundingBox(1).center) }
        runOnIdle { assertEquals(null, openedUri, "The owner is not part of the link") }
        onNodeWithTag("detail-title").performTouchInput { click(titleLayout.getBoundingBox("sample/".length + 1).center) }
        runOnIdle { assertEquals("https://huggingface.co/sample/Compact-Chat-GGUF", openedUri) }
        onNodeWithText("Variants").assertIsSelected()
        val viewport = onNodeWithTag("visual-root").fetchSemanticsNode().boundsInRoot
        val separator = onNodeWithTag("detail-section-divider").fetchSemanticsNode().boundsInRoot
        assertTrue(abs(separator.left - viewport.left) < 1f && abs(separator.right - viewport.right) < 1f)
        val filename = onNodeWithText("Compact-Chat-Q4_K_M.gguf").fetchSemanticsNode().boundsInRoot
        val divider = onNodeWithTag("detail-divider:Compact-Chat-Q4_K_M.gguf").fetchSemanticsNode().boundsInRoot
        assertTrue(abs(divider.left - filename.left) < 1f && abs(divider.right - viewport.right) < 1f)
        saveModelHubEvidence("details-tabs-390-light.png")
        runOnIdle { theme = ThemeMode.DARK }
        saveModelHubEvidence("details-tabs-390-dark.png")
        onNodeWithText("Technical details").performClick()
        onNodeWithText("Technical details").assertIsSelected()
        onNodeWithText("Advertised context").assertExists()
        saveModelHubEvidence("details-technical-390-dark.png")
        onNodeWithTag("detail-pager").performTouchInput { swipeRight() }
        onNodeWithText("Model card").assertIsSelected()
        onNodeWithTag("detail-pager").performTouchInput { swipeRight() }
        onNodeWithText("Variants").assertIsSelected()
        onNodeWithText("Compact-Chat-Q4_K_M.gguf").assertIsDisplayed()
        runOnIdle { width = 1040.dp; theme = ThemeMode.LIGHT }
        onNodeWithText("Technical details").performClick()
        onNodeWithTag("detail-support").assertIsDisplayed()
        saveModelHubEvidence("details-tabs-1040.png")
        runOnIdle { width = 390.dp }
        onNodeWithText("Repository").scrollDetailsIntoView().assertIsDisplayed()
        saveModelHubEvidence("details-technical-groups-390-light.png")
        runOnIdle { width = 320.dp; fontScale = 2f }
        onNodeWithText("40,960 tokens").scrollDetailsIntoView().assertIsDisplayed()
        onNodeWithText("en, hi").scrollDetailsIntoView().assertIsDisplayed()
        onNodeWithTag("detail-tabs").scrollDetailsIntoView()
        onNodeWithText("Variants").assertIsDisplayed()
        onNodeWithText("Model card").assertIsDisplayed()
        onNodeWithText("Technical details").assertIsDisplayed().assertIsSelected()
        val tabLayouts = mutableListOf<TextLayoutResult>()
        onNodeWithText("Technical details", useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(tabLayouts) }
        saveModelHubEvidence("details-tabs-320-200.png")
        val tabLayout = tabLayouts.single()
        // FlowRow can reuse a paragraph laid out at a wider constraint. Check the actual
        // glyphs, since didOverflowWidth compares that cached constraint with the new size.
        assertFalse(tabLayout.didOverflowHeight)
        repeat(tabLayout.lineCount) { assertFalse(tabLayout.isLineEllipsized(it)) }
        "Technical details".indices.forEach { index ->
            val glyph = tabLayout.getBoundingBox(index)
            assertTrue(glyph.left >= -1f && glyph.right <= tabLayout.size.width + 1f,
                "Tab character $index must fit within its visible width")
        }
        onNodeWithText("en, hi").scrollDetailsIntoView()
        saveModelHubEvidence("details-technical-320-200.png")
        onNodeWithText("Variants").performScrollTo().scrollDetailsIntoView().performClick()
        onNodeWithText("Compact-Chat-Q4_K_M.gguf").scrollDetailsIntoView().assertIsDisplayed()
        saveModelHubEvidence("details-variants-320-200.png")
        onNodeWithTag("detail-description").scrollDetailsIntoView().assertIsDisplayed()
        layouts.clear()
        onNodeWithTag("detail-description").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse(layouts.single().hasVisualOverflow, "Large text must also show the full excerpt")
    }
}
