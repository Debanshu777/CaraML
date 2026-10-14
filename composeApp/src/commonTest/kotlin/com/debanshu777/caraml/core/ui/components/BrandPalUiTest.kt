@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.core.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.ui.motion.LocalAuroraMotionPolicy
import com.debanshu777.caraml.core.ui.motion.auroraMotionPolicy
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class BrandPalUiTest {
    @Test
    fun reducedMotionChangesModeExpressionWithoutSpatialAnimationOrIdleLoop() = runComposeUiTest {
        mainClock.autoAdvance = false
        var appearance by mutableStateOf(BrandPalAppearance.Write)
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalAuroraMotionPolicy provides auroraMotionPolicy(0f)) {
                    Box(Modifier.size(88.dp).testTag("pal")) {
                        BrandPal(modifier = Modifier.size(88.dp), appearance = appearance)
                    }
                }
            }
        }
        val write = onNodeWithTag("pal").captureToImage().toPixelMap()
        runOnIdle { appearance = BrandPalAppearance.Imagine }
        mainClock.advanceTimeByFrame()
        val imagine = onNodeWithTag("pal").captureToImage().toPixelMap()
        mainClock.advanceTimeBy(1_000)
        val settled = onNodeWithTag("pal").captureToImage().toPixelMap()
        assertTrue(write.maximumDifference(imagine) > .1f, "Mode changes must remain visually distinct with reduced motion")
        assertTrue(imagine.maximumDifference(settled) <= 1f / 255f, "Reduced motion must settle immediately without an idle loop")
        runOnIdle { appearance = BrandPalAppearance.Animate }
        mainClock.advanceTimeByFrame()
        val animate = onNodeWithTag("pal").captureToImage().toPixelMap()
        assertTrue(imagine.maximumDifference(animate) > .1f, "Animate must have its own mode expression")
    }
}

private fun PixelMap.maximumDifference(other: PixelMap): Float {
    var maximum = 0f
    for (y in 0 until height) for (x in 0 until width) {
        val first = this[x, y]
        val second = other[x, y]
        maximum = maxOf(maximum, abs(first.red - second.red), abs(first.green - second.green), abs(first.blue - second.blue))
    }
    return maximum
}
