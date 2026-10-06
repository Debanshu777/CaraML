@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.CaraMLTheme
import com.debanshu777.caraml.core.theme.ThemePreferences
import com.debanshu777.caraml.core.ui.layout.AppContentKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.math.abs

class FrostedPageScaffoldUiTest {
    @Test
    fun landscapeWithoutFixedHeaderKeepsStatusBarGlassOverTheScrollingContent() = runComposeUiTest {
        var backdrop by mutableStateOf(Color.Red)
        var rowClicks = 0
        setContent {
            CaraMLTheme(ThemePreferences()) {
                Box(Modifier.requiredSize(820.dp, 360.dp).testTag("landscape-viewport")) {
                    FrostedPageScaffold(
                        kind = AppContentKind.ModelHub,
                        header = null,
                        safeInsets = WindowInsets(top = 24.dp, bottom = 24.dp),
                    ) { padding ->
                        Column(Modifier.fillMaxSize().testTag("landscape-scroll")
                            .verticalScroll(rememberScrollState()).padding(padding)) {
                            Box(Modifier.fillMaxWidth().height(1500.dp).background(backdrop)
                                .clickable { rowClicks++ })
                        }
                    }
                }
            }
        }
        onNodeWithTag("landscape-scroll").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) {
            it(0f, 300f)
        }
        val viewport = onNodeWithTag("landscape-viewport")
        val redPixels = viewport.captureToImage().toPixelMap()
        val scale = redPixels.width / 820f
        val x = redPixels.width / 2
        val y = (12 * scale).toInt()
        val red = redPixels[x, y]
        runOnIdle { backdrop = Color.Blue }
        val pixels = viewport.captureToImage().toPixelMap()
        val blue = pixels[x, y]
        assertTrue(red.red > blue.red + .15f && blue.blue > red.blue + .15f,
            "Landscape status glass must sample the scrolling content: red=$red blue=$blue")
        for (row in (23 * scale).toInt() until (57 * scale).toInt()) {
            val a = pixels[x, row]
            val b = pixels[x, row + 1]
            assertTrue(maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue)) < .04f,
                "The status-bar glass must fade without a horizontal seam at y=$row")
        }
        viewport.performTouchInput { click(Offset(x.toFloat(), y.toFloat())) }
        runOnIdle { assertEquals(0, rowClicks, "Status-bar glass must block the obscured row") }
        viewport.performTouchInput { click(Offset(x.toFloat(), 80 * scale)) }
        runOnIdle { assertEquals(1, rowClicks, "Visible content below the fade remains interactive") }
    }

    @Test
    fun bottomGlassSamplesContentThroughTheInsetAndLastActionScrollsClear() = runComposeUiTest {
        var backdrop by mutableStateOf(Color.Red)
        var lastActionClicks = 0
        setContent {
            CaraMLTheme(ThemePreferences()) {
                Box(Modifier.requiredSize(390.dp, 740.dp).testTag("bottom-viewport")) {
                    FrostedPageScaffold(
                        kind = AppContentKind.ModelHub,
                        safeInsets = WindowInsets(bottom = 24.dp),
                        header = { Spacer(Modifier.height(60.dp)) },
                    ) { padding ->
                        Column(Modifier.fillMaxSize().testTag("bottom-source-scroll")
                            .verticalScroll(rememberScrollState()).padding(padding)) {
                            Spacer(Modifier.fillMaxWidth().height(1500.dp).background(backdrop)
                                .clickable { lastActionClicks++ })
                            Box(Modifier.fillMaxWidth().height(56.dp).background(Color.Green)
                                .testTag("last-action").clickable { lastActionClicks++ })
                        }
                    }
                }
            }
        }
        val viewport = onNodeWithTag("bottom-viewport")
        val redPixels = viewport.captureToImage().toPixelMap()
        val scale = redPixels.width / 390f
        val x = redPixels.width / 2
        val y = redPixels.height - (12 * scale).toInt()
        val red = redPixels[x, y]
        runOnIdle { backdrop = Color.Blue }
        val blue = viewport.captureToImage().toPixelMap()[x, y]
        assertTrue(red.red > blue.red + .15f && blue.blue > red.blue + .15f,
            "The bottom inset must blur the continuing scroller, not a clipped wallpaper strip: red=$red blue=$blue")
        viewport.performTouchInput { click(Offset(x.toFloat(), y.toFloat())) }
        runOnIdle { assertEquals(0, lastActionClicks, "The gesture inset must block the row beneath it") }

        onNodeWithTag("bottom-source-scroll").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) {
            it(0f, 5000f)
        }
        val lastAction = onNodeWithTag("last-action")
        val glassTop = viewport.fetchSemanticsNode().boundsInRoot.bottom - (24 + 32) * scale
        assertTrue(lastAction.fetchSemanticsNode().boundsInRoot.bottom <= glassTop + 1f,
            "The final action must scroll fully above the gesture inset and its fading glass")
        viewport.performTouchInput { click(Offset(x.toFloat(), y.toFloat())) }
        runOnIdle { assertEquals(0, lastActionClicks, "The gesture inset must not activate list actions") }
        lastAction.performTouchInput { click() }
        runOnIdle { assertEquals(1, lastActionClicks) }
    }

    @Test
    fun glassSamplesTheScrolledContentInsteadOfOnlyTheWallpaper() = runComposeUiTest {
        var backdrop by mutableStateOf(Color.Red)
        var hiddenRowClicks = 0
        setContent {
            CaraMLTheme(ThemePreferences()) {
                Box(Modifier.requiredSize(390.dp, 740.dp).testTag("glass-viewport")) {
                    FrostedPageScaffold(
                        kind = AppContentKind.ModelHub,
                        safeInsets = WindowInsets(0),
                        header = { Spacer(Modifier.fillMaxWidth().height(120.dp)) },
                    ) { padding ->
                        Column(Modifier.fillMaxSize().testTag("glass-source-scroll")
                            .verticalScroll(rememberScrollState()).padding(padding)) {
                            Box(Modifier.fillMaxWidth().height(1500.dp).background(backdrop)
                                .clickable { hiddenRowClicks++ })
                        }
                    }
                }
            }
        }
        onNodeWithTag("glass-source-scroll").performTouchInput { swipeUp() }
        waitForIdle()
        val red = onNodeWithTag("page-sticky-chrome").captureToImage().toPixelMap()[190, 60]
        runOnIdle { backdrop = Color.Blue }
        waitForIdle()
        val blue = onNodeWithTag("page-sticky-chrome").captureToImage().toPixelMap()[190, 60]
        assertTrue(red.red > blue.red + .15f && blue.blue > red.blue + .15f,
            "Glass must respond to the content behind it: red=$red blue=$blue")
        onNodeWithTag("page-sticky-chrome").performTouchInput { click() }
        runOnIdle { assertEquals(0, hiddenRowClicks, "Glass must block taps on the obscured row") }

        val viewport = onNodeWithTag("glass-viewport")
        val pixels = viewport.captureToImage().toPixelMap()
        val headerBottom = (onNodeWithTag("page-sticky-chrome").fetchSemanticsNode().boundsInRoot.bottom -
            viewport.fetchSemanticsNode().boundsInRoot.top).toInt()
        val fadeHeight = (32f * pixels.width / 390f).toInt()
        val x = pixels.width / 2
        fun distance(a: Color, b: Color) = maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue))
        val frosted = pixels[x, headerBottom - 1]
        val clear = pixels[x, headerBottom + fadeHeight + 1]
        val middle = pixels[x, headerBottom + fadeHeight / 2]
        assertTrue(distance(frosted, clear) > .1f, "The fixture must visibly distinguish frost and content")
        assertTrue(distance(middle, frosted) > .03f && distance(middle, clear) > .03f,
            "The glass edge must pass through a partially transparent state")
        for (y in headerBottom - 1 until headerBottom + fadeHeight + 1) {
            assertTrue(distance(pixels[x, y], pixels[x, y + 1]) < .04f,
                "Glass must fade without a horizontal seam at y=$y")
        }
        viewport.performTouchInput { click(Offset(x.toFloat(), headerBottom + fadeHeight * .75f)) }
        runOnIdle { assertEquals(1, hiddenRowClicks, "The fade must leave visible content interactive") }
    }
}
