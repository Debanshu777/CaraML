package com.debanshu777.caraml.core.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertTrue

class AppActionColorTest {
    @Test
    fun characterAndStatusTextRemainReadableOnTheirRealSurfaces() {
        val brand = AppBrandColors()
        listOf(brand.yellow, brand.lilac, brand.mint, brand.accent).forEach { fill ->
            assertTrue(contrast(brand.ink, fill) >= 4.5f, "Brand ink must remain readable on $fill")
        }
        listOf(false, true).forEach { isDark ->
            val scheme = (if (isDark) darkColorScheme() else lightColorScheme()).withBrandSurfaces(isDark)
            listOf(brand.positiveText(isDark), brand.cautionText(isDark), scheme.onSurfaceVariant).forEach { text ->
                assertTrue(contrast(text, scheme.surface) >= 4.5f, "Status text must remain readable, dark=$isDark")
            }
        }
    }

    @Test
    fun everyPresetKeepsSmallActionTextReadableAcrossWarmSurfacesInBothModes() {
        listOf(false, true).forEach { isDark ->
            val scheme = (if (isDark) darkColorScheme() else lightColorScheme()).withBrandSurfaces(isDark)
            ThemeDefaults.PRESET_SEEDS.forEach { seed ->
                val foreground = actionColor(seed, isDark)
                listOf(
                    scheme.surface, scheme.surfaceDim, scheme.surfaceBright,
                    scheme.surfaceContainerLowest, scheme.surfaceContainerLow,
                    scheme.surfaceContainer, scheme.surfaceContainerHigh, scheme.surfaceContainerHighest,
                ).forEach { background ->
                    val lighter = maxOf(foreground.luminance(), background.luminance())
                    val darker = minOf(foreground.luminance(), background.luminance())
                    val contrast = (lighter + 0.05f) / (darker + 0.05f)
                    assertTrue(contrast >= 4.5f, "Action text contrast $contrast for seed $seed, dark=$isDark, background=$background")
                }
            }
        }
    }
}

private fun contrast(foreground: Color, background: Color): Float =
    (maxOf(foreground.luminance(), background.luminance()) + 0.05f) /
        (minOf(foreground.luminance(), background.luminance()) + 0.05f)
