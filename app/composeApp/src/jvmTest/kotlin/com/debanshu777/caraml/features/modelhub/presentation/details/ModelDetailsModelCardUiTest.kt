@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.features.modelhub.presentation.details

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.CaraMLTheme
import com.debanshu777.caraml.core.theme.ThemePreferences
import com.debanshu777.caraml.core.ui.components.FrostedPageScaffold
import com.debanshu777.caraml.core.ui.layout.AppContentKind
import com.debanshu777.caraml.features.modelhub.presentation.details.components.ModelDetailContent
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubBackHeader
import com.debanshu777.caraml.features.modelhub.presentation.search.saveModelHubEvidence
import com.debanshu777.caraml.sdk.discovery.huggingface.api.error.DataError
import com.debanshu777.caraml.sdk.discovery.huggingface.model.ModelCardDocument
import com.debanshu777.caraml.sdk.discovery.huggingface.model.ModelDetailResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ModelDetailsModelCardUiTest {
    @Test
    fun modelCardTabShowsTheFullDocumentTablesAndRelativeLinksWithRetry() = runComposeUiTest {
        val revision = "a".repeat(40)
        val longCell = "An evaluation description that wraps over several lines and must remain fully readable."
        val card = ModelCardDocument("sample/model", revision, """
            # Full model card

            [Usage guide](docs/guide.md#setup)

            | Evaluation | Result |
            | :--- | ---: |
            | $longCell | [Score details](./eval/report.md) |

            ## Training details

            This section appears after the overview excerpt and is part of the full README.

            ```python
            print("model card")
            ```
        """.trimIndent(), "A short overview excerpt.")
        var state by mutableStateOf<ModelCardUiState>(prepareModelCard(card))
        var opened: String? = null
        var retries = 0
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f), LocalUriHandler provides object : UriHandler {
                override fun openUri(uri: String) { opened = uri }
            }) {
                CaraMLTheme(ThemePreferences(reduceMotion = true)) {
                    Box(Modifier.requiredSize(390.dp, 780.dp).testTag("visual-root")) {
                        FrostedPageScaffold(AppContentKind.Details,
                            header = { ModelHubBackHeader({}, label = null) },
                            safeInsets = WindowInsets(0, 0, 0, 0)) { insets ->
                            ModelDetailContent(ModelDetailResponse(id = "sample/model"), emptyList(), false, { _, _, _ -> },
                                modelCardState = state, onRetryModelCard = { retries++ },
                                contentPadding = PaddingValues(top = insets.calculateTopPadding() + 12.dp, bottom = 16.dp))
                        }
                    }
                }
            }
        }
        onNodeWithText("Model card").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        onNodeWithText("Model card").performKeyInput { keyDown(Key.Enter); keyUp(Key.Enter) }
        onNodeWithText("Model card").assertIsSelected()
        onNodeWithText("A short overview excerpt.").assertIsDisplayed()
        waitUntil { onAllNodesWithText("Full model card").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Usage guide").scrollDetailsIntoView().performTouchInput { click(center) }
        runOnIdle { assertEquals("https://huggingface.co/sample/model/blob/$revision/docs/guide.md#setup", opened) }
        onNodeWithText(longCell, substring = true).scrollDetailsIntoView().assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        onNodeWithText(longCell, substring = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse(layouts.single().hasVisualOverflow, "README table cells must not be ellipsized")
        saveModelHubEvidence("model-card-table-390.png")
        onNodeWithText("Score details", substring = true).scrollDetailsIntoView().performTouchInput { click(center) }
        runOnIdle { assertEquals("https://huggingface.co/sample/model/blob/$revision/eval/report.md", opened) }
        onNodeWithText("Training details").scrollDetailsIntoView().assertIsDisplayed()
        runOnIdle { state = ModelCardUiState.Failed(DataError.Network.NoInternet) }
        onNodeWithText("Retry").scrollDetailsIntoView().performClick()
        runOnIdle { assertEquals(1, retries); state = ModelCardUiState.Missing }
        onNodeWithText("No README.md available for this model.").assertExists()
        onNodeWithText("Variants").scrollDetailsIntoView().performClick()
        onNodeWithText("No GGUF files found").assertExists()
    }
}
