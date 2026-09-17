@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PrismWorkbenchComponentsUiTest {

    @Test
    fun ordinaryClickableTechnicalRowUsesButtonSemanticsWithoutFalseSelectionState() =
        runComposeUiTest {
            var clicks = 0
            setContent {
                MaterialTheme {
                    TechnicalListRow(
                        title = "Open model",
                        onClick = { clicks += 1 },
                        modifier = Modifier
                            .width(360.dp)
                            .testTag("ordinary-technical-row"),
                    )
                }
            }

            val node = onNodeWithTag("ordinary-technical-row").fetchSemanticsNode()
            assertEquals(Role.Button, node.config[SemanticsProperties.Role])
            assertEquals(null, node.config.getOrNull(SemanticsProperties.Selected))
            onNodeWithTag("ordinary-technical-row").performClick()
            runOnIdle { assertEquals(1, clicks) }
        }

    @Test
    fun selectableTechnicalRowsExposeRadioStateForSelectedAndUnselectedOptions() =
        runComposeUiTest {
            var selectedPath = ""
            setContent {
                MaterialTheme {
                    Column {
                        TechnicalListRow(
                            title = "Unselected option",
                            selected = false,
                            emphasized = false,
                            selectionEnabled = true,
                            onClick = { selectedPath = "unselected" },
                            modifier = Modifier
                                .width(360.dp)
                                .testTag("unselected-option"),
                        )
                        TechnicalListRow(
                            title = "Selected option",
                            selected = true,
                            emphasized = true,
                            selectionEnabled = true,
                            onClick = {},
                            modifier = Modifier
                                .width(360.dp)
                                .testTag("selected-option"),
                        )
                    }
                }
            }

            listOf("unselected-option" to false, "selected-option" to true)
                .forEach { (tag, selected) ->
                    val node = onNodeWithTag(tag).fetchSemanticsNode()
                    assertEquals(Role.RadioButton, node.config[SemanticsProperties.Role])
                    assertEquals(selected, node.config[SemanticsProperties.Selected])
                }
            onNodeWithTag("unselected-option").performClick()
            runOnIdle { assertEquals("unselected", selectedPath) }
        }

    @Test
    fun legacyAndEmphasizedPositionalSignaturesRemainCallable() = runComposeUiTest {
        var legacyClicks = 0
        var emphasizedClicks = 0
        setContent {
            MaterialTheme {
                Column {
                    TechnicalListRow(
                        "Legacy positional",
                        Modifier.testTag("legacy-positional-row"),
                        "legacy-owner",
                        "legacy-metadata",
                        "Legacy positional row",
                        false,
                        SignalTone.Neutral,
                        { legacyClicks += 1 },
                        null,
                        null,
                    ) {
                        Text("Legacy trailing")
                    }
                    TechnicalListRow(
                        "Emphasized positional",
                        Modifier.testTag("emphasized-positional-row"),
                        "current-owner",
                        "current-metadata",
                        "Emphasized positional row",
                        false,
                        true,
                        SignalTone.Accent,
                        { emphasizedClicks += 1 },
                        null,
                        null,
                    ) {
                        Text("Emphasized trailing")
                    }
                }
            }
        }

        onNodeWithTag("legacy-positional-row").performClick()
        onNodeWithTag("emphasized-positional-row").performClick()
        runOnIdle {
            assertEquals(1, legacyClicks)
            assertEquals(1, emphasizedClicks)
        }
    }

    @Test
    fun productionTechnicalRowUsesExactModelMetadataAndTechnicalRoles() = runComposeUiTest {
        setContent {
            MaterialTheme {
                TechnicalListRow(
                    title = "Semantic model title",
                    eyebrow = "ORG / FAMILY",
                    metadata = "Q4_K_M · 4.7 GB",
                    modifier = Modifier.width(360.dp),
                )
            }
        }

        textStyleFor("Semantic model title").let { style ->
            assertEquals(17.sp, style.fontSize)
            assertEquals(22.sp, style.lineHeight)
            assertEquals(FontWeight.Medium, style.fontWeight)
        }
        textStyleFor("Q4_K_M · 4.7 GB").let { style ->
            assertEquals(13.sp, style.fontSize)
            assertEquals(18.sp, style.lineHeight)
            assertEquals(FontWeight.Normal, style.fontWeight)
        }
        textStyleFor("ORG / FAMILY").let { style ->
            assertEquals(12.sp, style.fontSize)
            assertEquals(16.sp, style.lineHeight)
            assertEquals(FontWeight.Medium, style.fontWeight)
            assertEquals(FontFamily.Monospace, style.fontFamily)
        }
    }

    @Test
    fun commandSurfaceFillMaxWidthWrapsContentAndLeavesFollowingSiblingVisible() =
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    MaterialTheme {
                        Column(
                            modifier = Modifier.requiredSize(width = 360.dp, height = 180.dp),
                        ) {
                            CommandSurface(
                                focused = false,
                                active = false,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("content-height-command"),
                            ) {
                                Text("Run locally")
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("following-content"),
                            )
                        }
                    }
                }
            }

            val command = onNodeWithTag("content-height-command").fetchSemanticsNode()
            assertTrue(
                command.boundsInRoot.height <= 72f,
                "Command surface must wrap its content; height was ${command.boundsInRoot.height}dp",
            )
            onNodeWithTag("following-content").assertHeightIsAtLeast(48.dp)
        }

    @Test
    fun commandSurfaceShowsSeedDerivedFocusAtBothEdgesWithoutFillingItsContent() =
        runComposeUiTest {
            val scheme = darkColorScheme(
                surface = Color(0xFF0E1118),
                surfaceContainer = Color(0xFF222630),
                primary = Color(0xFF4F83FF),
                tertiary = Color(0xFFFF5AA5),
            )
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    MaterialTheme(colorScheme = scheme) {
                        Box(Modifier.background(scheme.surface)) {
                            CommandSurface(
                                focused = true,
                                active = false,
                                modifier = Modifier
                                    .requiredSize(width = 360.dp, height = 96.dp)
                                    .testTag("focused-command"),
                            ) {
                                Box(Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }

            val pixels = onNodeWithTag("focused-command").captureToImage().toPixelMap()
            val leadingEdge = pixels[1, 48]
            val center = pixels[180, 48]
            val trailingEdge = pixels[358, 48]

            assertTrue(
                leadingEdge.colorDistance(scheme.surface) >= 0.05f,
                "Focused command surface must show its primary-derived leading edge",
            )
            assertTrue(
                trailingEdge.colorDistance(scheme.surface) >= 0.05f,
                "Focused command surface must show its tertiary-derived trailing edge",
            )
            assertTrue(
                center.colorDistance(scheme.surfaceContainer) <= 0.025f,
                "Focus treatment must not fill the command content; center was $center",
            )
            assertTrue(
                leadingEdge.blue - leadingEdge.red > trailingEdge.blue - trailingEdge.red,
                "The two edges must preserve distinct primary and tertiary seed roles",
            )
        }

    @Test
    fun technicalListRowUsesDividerGrammarAndKeepsACompactHeight() = runComposeUiTest {
        val scheme = darkColorScheme(
            surface = Color(0xFF101217),
            onSurface = Color.White,
            onSurfaceVariant = Color(0xFFCBD0DC),
            outlineVariant = Color(0xFF9298A5),
        )
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme(colorScheme = scheme) {
                    Box(
                        modifier = Modifier
                            .width(360.dp)
                            .background(scheme.surface),
                    ) {
                        TechnicalListRow(
                            title = "Qwen 2.5 7B",
                            metadata = "Q4_K_M · 4.7 GB",
                            modifier = Modifier.testTag("technical-row"),
                        )
                    }
                }
            }
        }

        val row = onNodeWithTag("technical-row").fetchSemanticsNode()
        assertTrue(row.boundsInRoot.height <= 112f, "Compact row was ${row.boundsInRoot.height}dp")

        val pixels = onNodeWithTag("technical-row").captureToImage().toPixelMap()
        val dividerY = pixels.height - 1
        assertTrue(
            pixels[4, dividerY].colorDistance(scheme.surface) <= 0.025f,
            "Divider must stay inset from the page edge",
        )
        assertTrue(
            pixels[20, dividerY].colorDistance(scheme.surface) >= 0.05f,
            "A bottom divider must structure the row instead of a card outline",
        )
        assertTrue(
            pixels[4, pixels.height / 2].colorDistance(scheme.surface) <= 0.025f,
            "An unselected technical row must not draw a side card outline",
        )
    }

    @Test
    fun selectedTechnicalRowHasOneSignalRailAndNonColorSelectionSemantics() = runComposeUiTest {
        val scheme = darkColorScheme(
            surface = Color(0xFF101217),
            surfaceContainerHigh = Color(0xFF2B303A),
            onSurface = Color.White,
            primary = Color(0xFF4F83FF),
        )
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme(colorScheme = scheme) {
                    TechnicalListRow(
                        title = "Selected artifact",
                        metadata = "Q5_K_M · 5.1 GB",
                        selected = true,
                        signalTone = SignalTone.Accent,
                        modifier = Modifier
                            .width(360.dp)
                            .testTag("selected-technical-row"),
                    )
                }
            }
        }

        onNodeWithTag("selected-technical-row").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.Selected, true),
        )
        val pixels = onNodeWithTag("selected-technical-row").captureToImage().toPixelMap()
        val accentColumns = (0 until pixels.width).count { x ->
            pixels[x, 8].colorDistance(scheme.surfaceContainerHigh) >= 0.08f
        }
        assertEquals(3, accentColumns, "Selected row must use exactly one 3dp signal rail")
    }

    @Test
    fun statusMarkPaintsCompactlyButInteractiveParentRetainsFortyEightDpTarget() =
        runComposeUiTest {
            val scheme = darkColorScheme(
                primaryContainer = Color(0xFF294A88),
                onPrimaryContainer = Color.White,
            )
            var clicks = 0
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    MaterialTheme(colorScheme = scheme) {
                        Box(
                            modifier = Modifier
                                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                .clickable { clicks += 1 }
                                .testTag("status-target"),
                            contentAlignment = Alignment.Center,
                        ) {
                            StatusMark(
                                label = "Ready",
                                contentDescription = "Ready for local inference",
                                tone = SignalTone.Accent,
                                icon = Icons.Default.CheckCircle,
                                modifier = Modifier.testTag("status-mark"),
                            )
                        }
                    }
                }
            }

            val mark = onNodeWithTag("status-mark").fetchSemanticsNode()
            assertTrue(mark.boundsInRoot.height < 48f, "Status paint must stay compact")
            onNodeWithTag("status-target")
                .assertHeightIsAtLeast(48.dp)
                .performClick()
            runOnIdle { assertEquals(1, clicks) }
            onNode(hasStateDescription("Ready for local inference") and hasText("Ready"))
                .assertExists()

            val pixels = onNodeWithTag("status-mark").captureToImage().toPixelMap()
            assertTrue(
                pixels.maximumContrastAgainst(
                    background = scheme.primaryContainer,
                    bounds = Rect(8f, 4f, 22f, (pixels.height - 4).toFloat()),
                ) >= 3f,
                "Status mark must paint its required icon with non-text contrast",
            )
        }
}

private fun ComposeUiTest.textStyleFor(text: String) =
    mutableListOf<TextLayoutResult>().also { results ->
        onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                assertTrue(action(results), "Expected a text layout result for $text")
            }
        assertEquals(1, results.size)
    }.single().layoutInput.style

private fun hasStateDescription(description: String) =
    SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, description)

private fun PixelMap.maximumContrastAgainst(background: Color, bounds: Rect): Float {
    val left = bounds.left.toInt().coerceIn(0, width - 1)
    val top = bounds.top.toInt().coerceIn(0, height - 1)
    val right = bounds.right.toInt().coerceIn(left + 1, width)
    val bottom = bounds.bottom.toInt().coerceIn(top + 1, height)
    var maximum = 1f
    for (y in top until bottom) {
        for (x in left until right) {
            maximum = maxOf(maximum, contrastRatio(this[x, y], background))
        }
    }
    return maximum
}

private fun contrastRatio(first: Color, second: Color): Float {
    val firstLuminance = first.luminance()
    val secondLuminance = second.luminance()
    return (maxOf(firstLuminance, secondLuminance) + 0.05f) /
        (minOf(firstLuminance, secondLuminance) + 0.05f)
}

private fun Color.colorDistance(other: Color): Float =
    abs(red - other.red) + abs(green - other.green) + abs(blue - other.blue)
