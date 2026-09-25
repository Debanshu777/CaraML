package com.debanshu777.caraml.core.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class ThemeDefaultsTest {
    @Test
    fun firstLaunchUsesTheExactBaselineYellowWithExpressiveHarmonies() {
        val preferences = ThemePreferences()

        assertEquals(Color(0xFFEFD04B), preferences.seedColor)
        assertEquals(ThemePaletteStyle.EXPRESSIVE, preferences.paletteStyle)
    }
}
