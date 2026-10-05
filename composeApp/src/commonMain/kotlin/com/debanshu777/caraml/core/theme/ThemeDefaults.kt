package com.debanshu777.caraml.core.theme

import androidx.compose.ui.graphics.Color

/**
 * Theme defaults plus a curated palette of seed colors offered to the user
 * in the Appearance settings picker.
 */
object ThemeDefaults {
    /** Warm orange — the first-launch action accent for the Pocket pal identity. */
    val DEFAULT_SEED_COLOR: Color = Color(0xFFFF7854)

    val DEFAULT_THEME_MODE: ThemeMode = ThemeMode.SYSTEM

    val DEFAULT_PALETTE_STYLE: ThemePaletteStyle = ThemePaletteStyle.EXPRESSIVE

    /**
     * Twelve seed colors covering the M3 reference hues. Kept in a stable order
     * so the picker grid layout doesn't shuffle between releases.
     */
    val PRESET_SEEDS: List<Color> = listOf(
        DEFAULT_SEED_COLOR, // CaraML orange
        Color(0xFF6750A4), // Purple
        Color(0xFF1565C0), // Indigo
        Color(0xFF0288D1), // Cerulean
        Color(0xFF00897B), // Teal
        Color(0xFF2E7D32), // Forest
        Color(0xFFAFB42B), // Chartreuse
        Color(0xFFEFD04B), // Baseline yellow
        Color(0xFFD81B60), // Magenta
        Color(0xFFC62828), // Crimson
        Color(0xFF5D4037), // Cocoa
        Color(0xFF455A64), // Slate
    )
}
