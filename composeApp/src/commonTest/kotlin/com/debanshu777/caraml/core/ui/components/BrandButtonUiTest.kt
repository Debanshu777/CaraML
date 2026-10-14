@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.core.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.drawer.BrandNavigationIcons
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.core.theme.CaraMLTheme
import com.debanshu777.caraml.core.theme.ThemeMode
import com.debanshu777.caraml.core.theme.ThemePreferences
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrandButtonUiTest {
    @Test
    fun quickTapInsideLazyColumnDepressesBeforeRestoringWithoutDelayingTheClick() = runComposeUiTest {
        mainClock.autoAdvance = false
        var clicks = 0
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                CaraMLTheme(ThemePreferences(themeMode = ThemeMode.LIGHT)) {
                    LazyColumn(Modifier.height(180.dp)) {
                        item {
                            BrandButton({ clicks++ }, Modifier.testTag("quick")) { Text("Download") }
                        }
                    }
                }
            }
        }
        val button = onNodeWithTag("quick")
        val target = button.fetchSemanticsNode().boundsInRoot
        val restingLabel = onNodeWithText("Download", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        button.performTouchInput { click() }
        assertEquals(1, clicks, "The action must fire without waiting for its visual feedback")
        mainClock.advanceTimeBy(80)
        val depressedLabel = onNodeWithText("Download", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(abs(depressedLabel.top - restingLabel.top - 4f) < .5f, "A quick tap in a scrollable must visibly collapse the base")
        assertEquals(target, button.fetchSemanticsNode().boundsInRoot)
        mainClock.advanceTimeBy(1_000)
        assertEquals(restingLabel, onNodeWithText("Download", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot)
        assertEquals(1, clicks)
    }

    @Test
    fun cancellationAndDisableClearMinimumFeedbackImmediately() = runComposeUiTest {
        mainClock.autoAdvance = false
        val source = MutableInteractionSource()
        var enabled by mutableStateOf(true)
        var clicks = 0
        setContent {
            CaraMLTheme(ThemePreferences(themeMode = ThemeMode.DARK, reduceMotion = true)) {
                BrandButton({ clicks++ }, Modifier.testTag("action"), enabled = enabled,
                    style = BrandButtonStyle.Secondary, interactionSource = source) { Text("Cancel") }
            }
        }
        val button = onNodeWithTag("action")
        val restingImage = button.captureToImage().toPixelMap()
        val cancelledPress = PressInteraction.Press(Offset.Zero)
        runOnIdle { assertTrue(source.tryEmit(cancelledPress)) }
        mainClock.advanceTimeByFrame()
        assertTrue(restingImage.difference(button.captureToImage().toPixelMap()) > .1f)
        runOnIdle { assertTrue(source.tryEmit(PressInteraction.Cancel(cancelledPress))) }
        mainClock.advanceTimeByFrame()
        assertTrue(restingImage.difference(button.captureToImage().toPixelMap()) < .01f, "Cancel must bypass the minimum hold")

        runOnIdle { assertTrue(source.tryEmit(PressInteraction.Press(Offset.Zero))) }
        mainClock.advanceTimeByFrame()
        runOnIdle { enabled = false }
        mainClock.advanceTimeByFrame()
        button.assertIsNotEnabled()
        runOnIdle { enabled = true }
        mainClock.advanceTimeByFrame()
        assertTrue(restingImage.difference(button.captureToImage().toPixelMap()) < .01f, "Re-enabling must not resume stale press feedback")
        assertEquals(0, clicks)
    }

    @Test
    fun pressKeepsTargetStableAndReleaseOrCancellationRestoresFace() = runComposeUiTest {
        mainClock.autoAdvance = false
        var clicks = 0
        val source = MutableInteractionSource()
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                CaraMLTheme(ThemePreferences(themeMode = ThemeMode.LIGHT)) {
                    BrandButton({ clicks++ }, Modifier.testTag("action").heightIn(min = 44.dp), interactionSource = source) {
                        Text("Create")
                    }
                }
            }
        }
        val action = onNodeWithTag("action")
        action.assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        val target = action.fetchSemanticsNode().boundsInRoot
        val restingLabel = onNodeWithText("Create", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

        action.performTouchInput { down(center) }
        mainClock.advanceTimeBy(1_000)
        assertEquals(target, action.fetchSemanticsNode().boundsInRoot)
        val pressedLabel = onNodeWithText("Create", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(abs(pressedLabel.top - restingLabel.top - 4f) < .5f, "Only the face should sink into its four-pixel base")
        action.performTouchInput { up() }
        mainClock.advanceTimeBy(1_000)
        assertEquals(restingLabel, onNodeWithText("Create", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot)
        assertEquals(1, clicks)

        // Skiko's synthetic touch cancel is a no-op. Exercise the public interaction contract.
        val cancelledPress = PressInteraction.Press(Offset.Zero)
        runOnIdle { assertTrue(source.tryEmit(cancelledPress)) }
        mainClock.advanceTimeBy(1_000)
        assertEquals(pressedLabel, onNodeWithText("Create", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot)
        runOnIdle { assertTrue(source.tryEmit(PressInteraction.Cancel(cancelledPress))) }
        mainClock.advanceTimeBy(1_000)
        assertEquals(restingLabel, onNodeWithText("Create", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot)
        assertEquals(target, action.fetchSemanticsNode().boundsInRoot)
        assertEquals(1, clicks, "Cancellation must not invoke the action")
    }

    @Test
    fun darkReducedMotionShowsPressFeedbackWithoutMovingTheFace() = runComposeUiTest {
        mainClock.autoAdvance = false
        val source = MutableInteractionSource()
        setContent {
            CaraMLTheme(ThemePreferences(themeMode = ThemeMode.DARK, reduceMotion = true)) {
                BrandButton({}, Modifier.testTag("action"), style = BrandButtonStyle.Secondary, interactionSource = source) { Text("Cancel") }
            }
        }
        val action = onNodeWithTag("action")
        val restingBounds = onNodeWithText("Cancel", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val restingImage = action.captureToImage().toPixelMap()
        val press = PressInteraction.Press(Offset.Zero)
        runOnIdle { assertTrue(source.tryEmit(press)) }
        mainClock.advanceTimeByFrame()
        val pressedImage = action.captureToImage().toPixelMap()
        assertEquals(restingBounds, onNodeWithText("Cancel", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot)
        assertTrue(restingImage.difference(pressedImage) > .1f, "Reduced motion must retain visible pressed feedback")
        mainClock.advanceTimeBy(1_000)
        assertTrue(pressedImage.difference(action.captureToImage().toPixelMap()) < .01f, "Reduced motion must be static while held")
        runOnIdle { assertTrue(source.tryEmit(PressInteraction.Cancel(press))) }
        mainClock.advanceTimeByFrame()
        assertTrue(restingImage.difference(action.captureToImage().toPixelMap()) < .01f)
    }

    @Test
    fun keyboardActivationAndDisabledIconSemanticsRemainIntact() = runComposeUiTest {
        val focus = FocusRequester()
        var clicks = 0
        setContent {
            CaraMLTheme(ThemePreferences(themeMode = ThemeMode.LIGHT, reduceMotion = true)) {
                Surface(color = AppTheme.colors.background) {
                    Column(Modifier.padding(12.dp)) {
                        BrandButton({ clicks++ }, Modifier.testTag("keyboard").focusRequester(focus)) { Text("Download") }
                        BrandIconButton({ clicks++ }, Modifier.testTag("disabled"), enabled = false) {
                            Icon(BrandNavigationIcons.Close, "Remove model")
                        }
                    }
                }
            }
        }
        runOnIdle { focus.requestFocus() }
        onNodeWithTag("keyboard").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        assertEquals(1, clicks)
        onNodeWithContentDescription("Remove model").assertIsNotEnabled()
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            .performTouchInput { click() }
        assertEquals(1, clicks)
        val disabled = onNodeWithTag("disabled").captureToImage().toPixelMap()
        val bottom = disabled[disabled.width / 2, disabled.height - 2]
        assertTrue(bottom.red > .8f && bottom.green > .8f, "Disabled controls should have no solid dark extrusion")
    }

    @Test
    fun largeLabelsGrowBeyondTheMinimumTouchHeight() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                CaraMLTheme(ThemePreferences(themeMode = ThemeMode.LIGHT)) {
                    BrandButton({}, Modifier.testTag("large").heightIn(min = 44.dp)) { Text("Finish setup") }
                }
            }
        }
        onNodeWithTag("large").assertHeightIsAtLeast(60.dp)
    }
}

private fun PixelMap.difference(other: PixelMap): Float {
    var largest = 0f
    for (y in 0 until height) for (x in 0 until width) {
        largest = maxOf(largest, abs(this[x, y].red - other[x, y].red), abs(this[x, y].green - other[x, y].green), abs(this[x, y].blue - other[x, y].blue))
    }
    return largest
}
