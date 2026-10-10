package com.debanshu777.caraml.core.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class ThemeDefaultsTest {
    @Test
    fun firstLaunchUsesThePocketPalOrangeWithExpressiveHarmonies() {
        val preferences = ThemePreferences()

        assertEquals(Color(0xFFFF7854), preferences.seedColor)
        assertEquals(ThemePaletteStyle.EXPRESSIVE, preferences.paletteStyle)
    }
}
