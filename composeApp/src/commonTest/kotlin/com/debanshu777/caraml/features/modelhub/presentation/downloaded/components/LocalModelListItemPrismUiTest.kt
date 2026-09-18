@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.features.modelhub.presentation.downloaded.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalModelListItemPrismUiTest {

    @Test
    fun localRowUsesPageWidthDividerGrammarWithoutAnIndependentPane() = runComposeUiTest {
        val canvas = Color(0xFF101217)
        val oldPane = Color(0xFF7B3048)
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme(
                    colorScheme = darkColorScheme(
                        surface = canvas,
                        surfaceContainer = oldPane,
                        onSurface = Color.White,
                        onSurfaceVariant = Color(0xFFD0D4DE),
                    ),
                ) {
                    Box(
                        Modifier
                            .requiredSize(width = 360.dp, height = 160.dp)
                            .background(canvas)
                            .testTag("local-row-host"),
                    ) {
                        LocalModelListItem(
                            model = localModel(
                                modelId = "org/local-model",
                                filename = "local-model.gguf",
                            ),
                            selectionMode = false,
                            isSelected = false,
                            onOpenModel = {},
                            onToggleSelect = {},
                            onLongPress = {},
                            modifier = Modifier.testTag("local-row"),
                        )
                    }
                }
            }
        }

        val host = onNodeWithTag("local-row-host")
        val hostBounds = host.fetchSemanticsNode().boundsInRoot
        val rowBounds = onNodeWithTag("local-row").fetchSemanticsNode().boundsInRoot
        val titleBounds = onNodeWithText("org/local-model", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            titleBounds.left - hostBounds.left <= 20f,
            "Library identity must use the page grid once; left=${titleBounds.left - hostBounds.left}",
        )

        val pixels = host.captureToImage().toPixelMap()
        val clearEdgePixel = pixels[
            (340f - hostBounds.left).toInt().coerceIn(0, pixels.width - 1),
            (rowBounds.center.y - hostBounds.top).toInt().coerceIn(0, pixels.height - 1),
        ]
        assertTrue(
            clearEdgePixel.colorDistance(canvas) <= 0.03f,
            "An ordinary library row must stay on the canvas instead of painting its own pane",
        )
    }

    @Test
    fun openSelectionAndLongPressKeepExactIndependentCallbacks() = runComposeUiTest {
        var selectionMode by mutableStateOf(false)
        var selected by mutableStateOf(false)
        var opens = 0
        var toggles = 0
        var longPresses = 0
        setContent {
            MaterialTheme {
                LocalModelListItem(
                    model = localModel("org/interactive", "interactive.gguf"),
                    selectionMode = selectionMode,
                    isSelected = selected,
                    onOpenModel = { opens += 1 },
                    onToggleSelect = {
                        toggles += 1
                        selected = !selected
                    },
                    onLongPress = { longPresses += 1 },
                )
            }
        }

        val openNode = onNodeWithContentDescription("Open model org/interactive")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
        val openConfig = openNode.fetchSemanticsNode().config
        assertEquals(Role.Button, openConfig[SemanticsProperties.Role])
        assertEquals(null, openConfig.getOrNull(SemanticsProperties.Selected))
        openNode.performClick()
        openNode.performSemanticsAction(SemanticsActions.OnLongClick)
        runOnIdle {
            assertEquals(1, opens)
            assertEquals(0, toggles)
            assertEquals(1, longPresses)
            selectionMode = true
        }

        val selectionNode = onNodeWithContentDescription(
            "Not selected org/interactive, tap to select",
        )
        val selectionConfig = selectionNode.fetchSemanticsNode().config
        assertEquals(Role.Checkbox, selectionConfig[SemanticsProperties.Role])
        assertEquals(false, selectionConfig[SemanticsProperties.Selected])
        selectionNode.performClick()
        runOnIdle {
            assertEquals(1, opens)
            assertEquals(1, toggles)
            assertTrue(selected)
        }
        onNodeWithContentDescription("Selected org/interactive, tap to deselect")
            .assertIsDisplayed()
    }

    @Test
    fun partialRowKeepsReadinessAndIndependentFixComponentsAction() = runComposeUiTest {
        var opens = 0
        var fixes = 0
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f)) {
                MaterialTheme {
                    Box(
                        Modifier
                            .requiredSize(width = 360.dp, height = 400.dp)
                            .testTag("partial-row-host"),
                    ) {
                        LocalModelListItem(
                            model = localModel(
                                modelId = "org/partial",
                                filename = "partial.safetensors",
                                componentStatus = LocalModelEntity.STATUS_PARTIAL,
                            ),
                            selectionMode = false,
                            isSelected = false,
                            onOpenModel = { opens += 1 },
                            onToggleSelect = {},
                            onLongPress = {},
                            onFixComponents = { fixes += 1 },
                        )
                    }
                }
            }
        }

        onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                "Partial download. Missing components need setup.",
            ),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        val action = onNodeWithText("Download missing components")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        val hostBounds = onNodeWithTag("partial-row-host").fetchSemanticsNode().boundsInRoot
        val actionBounds = action.fetchSemanticsNode().boundsInRoot
        assertTrue(
            actionBounds.left >= hostBounds.left && actionBounds.right <= hostBounds.right,
            "The 200% repair action must remain inside the 360dp row: $actionBounds",
        )
        action.performClick()
        runOnIdle {
            assertEquals(0, opens)
            assertEquals(1, fixes)
        }
    }

    @Test
    fun unsupportedIdentityTextMeetsContrastInDarkAndLightThemes() = runComposeUiTest {
        val darkCanvas = Color(0xFF101217)
        val lightCanvas = Color(0xFFF9FAFF)
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Column {
                    MaterialTheme(
                        colorScheme = darkColorScheme(
                            surface = darkCanvas,
                            surfaceContainer = darkCanvas,
                            onSurface = Color.White,
                            onSurfaceVariant = Color(0xFFD0D4DE),
                        ),
                    ) {
                        UnsupportedRowFixture(
                            modelId = "unsupported-dark",
                            canvas = darkCanvas,
                            hostTag = "unsupported-dark-host",
                        )
                    }
                    MaterialTheme(
                        colorScheme = lightColorScheme(
                            surface = lightCanvas,
                            surfaceContainer = lightCanvas,
                            onSurface = Color.Black,
                            onSurfaceVariant = Color(0xFF333640),
                        ),
                    ) {
                        UnsupportedRowFixture(
                            modelId = "unsupported-light",
                            canvas = lightCanvas,
                            hostTag = "unsupported-light-host",
                        )
                    }
                }
            }
        }

        listOf(
            Triple("unsupported-dark-host", "unsupported-dark", darkCanvas),
            Triple("unsupported-light-host", "unsupported-light", lightCanvas),
        ).forEach { (hostTag, title, canvas) ->
            val host = onNodeWithTag(hostTag)
            val hostBounds = host.fetchSemanticsNode().boundsInRoot
            val titleBounds = onNodeWithText(title, useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            val pixels = host.captureToImage().toPixelMap()
            var strongestContrast = 1f
            val left = (titleBounds.left - hostBounds.left).toInt().coerceIn(0, pixels.width - 1)
            val right = (titleBounds.right - hostBounds.left).toInt().coerceIn(left + 1, pixels.width)
            val top = (titleBounds.top - hostBounds.top).toInt().coerceIn(0, pixels.height - 1)
            val bottom = (titleBounds.bottom - hostBounds.top).toInt().coerceIn(top + 1, pixels.height)
            for (y in top until bottom) {
                for (x in left until right) {
                    strongestContrast = max(strongestContrast, contrastRatio(canvas, pixels[x, y]))
                }
            }
            assertTrue(
                strongestContrast >= 4.5f,
                "$title identity text must remain readable; contrast was $strongestContrast:1",
            )
        }
    }
}

@androidx.compose.runtime.Composable
private fun UnsupportedRowFixture(
    modelId: String,
    canvas: Color,
    hostTag: String,
) {
    Box(
        Modifier
            .requiredSize(width = 360.dp, height = 180.dp)
            .background(canvas)
            .testTag(hostTag),
    ) {
        LocalModelListItem(
            model = localModel(
                modelId = modelId,
                filename = "$modelId.bin",
                pipelineTag = "audio-classification",
            ),
            selectionMode = false,
            isSelected = false,
            onOpenModel = {},
            onToggleSelect = {},
            onLongPress = {},
        )
    }
}

private fun localModel(
    modelId: String,
    filename: String,
    componentStatus: String? = null,
    pipelineTag: String? = "text-generation",
) = LocalModelEntity(
    modelId = modelId,
    filename = filename,
    localPath = "/models/$filename",
    sizeBytes = 1_073_741_824L,
    downloadedAt = 0L,
    author = modelId.substringBefore('/'),
    libraryName = "llama.cpp",
    pipelineTag = pipelineTag,
    componentStatus = componentStatus,
)

private fun Color.colorDistance(other: Color): Float =
    abs(red - other.red) + abs(green - other.green) + abs(blue - other.blue)

private fun contrastRatio(first: Color, second: Color): Float {
    val light = max(first.luminance(), second.luminance())
    val dark = min(first.luminance(), second.luminance())
    return (light + 0.05f) / (dark + 0.05f)
}
