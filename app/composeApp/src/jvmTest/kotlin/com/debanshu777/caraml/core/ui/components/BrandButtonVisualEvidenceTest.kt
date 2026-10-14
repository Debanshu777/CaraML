@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.drawer.BrandNavigationIcons
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.core.theme.CaraMLTheme
import com.debanshu777.caraml.core.theme.ThemeMode
import com.debanshu777.caraml.core.theme.ThemePreferences
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

class BrandButtonVisualEvidenceTest {
    @Test
    fun restingPressedAndDarkReducedButtonEvidence() = runComposeUiTest {
        mainClock.autoAdvance = false
        var mode by mutableStateOf(ThemeMode.LIGHT)
        var reduced by mutableStateOf(false)
        setContent {
            CaraMLTheme(ThemePreferences(themeMode = mode, reduceMotion = reduced)) {
                Surface(Modifier.testTag("button-gallery"), color = AppTheme.colors.background) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        BrandButton({}, Modifier.testTag("primary")) { Text("Let's go") }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            BrandButton({}, Modifier.testTag("secondary"), style = BrandButtonStyle.Secondary) { Text("Cancel") }
                            BrandButton({}, style = BrandButtonStyle.Destructive) { Text("Delete") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            BrandButton({}, enabled = false) { Text("Unavailable") }
                            BrandIconButton({}) { Icon(BrandNavigationIcons.Close, "Close") }
                        }
                    }
                }
            }
        }
        saveButtonEvidence("brand-buttons-rest.png")
        onNodeWithTag("primary").performTouchInput { down(center) }
        mainClock.advanceTimeBy(1_000)
        saveButtonEvidence("brand-buttons-pressed.png")
        onNodeWithTag("primary").performTouchInput { up() }
        mainClock.advanceTimeBy(1_000)
        runOnIdle { mode = ThemeMode.DARK; reduced = true }
        mainClock.advanceTimeByFrame()
        saveButtonEvidence("brand-buttons-dark-rest.png")
        onNodeWithTag("secondary").performTouchInput { down(center) }
        mainClock.advanceTimeByFrame()
        saveButtonEvidence("brand-buttons-dark-reduced-pressed.png")
        onNodeWithTag("secondary").performTouchInput { up() }
    }
}

private fun ComposeUiTest.saveButtonEvidence(name: String) {
    val directory = System.getenv("CARAML_VISUAL_EVIDENCE_DIR")?.takeIf(String::isNotBlank) ?: return
    val pixels = onNodeWithTag("button-gallery").captureToImage().toPixelMap()
    val image = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
        val color = pixels[x, y]
        fun channel(value: Float) = (value.coerceIn(0f, 1f) * 255f).toInt()
        image.setRGB(x, y, (channel(color.alpha) shl 24) or (channel(color.red) shl 16) or (channel(color.green) shl 8) or channel(color.blue))
    }
    val target = File(directory, name)
    target.parentFile.mkdirs()
    ImageIO.write(image, "png", target)
}
