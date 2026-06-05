package com.debanshu777.caraml.core.theme

import androidx.compose.ui.graphics.Color

/**
 * Persisted user preferences that drive [CaraMLTheme].
 *
 * Default values are sourced from [ThemeDefaults] so every layer (DataStore reads,
 * ViewModel initial state, previews) sees the same starting point.
 */
data class ThemePreferences(
    val seedColor: Color = ThemeDefaults.DEFAULT_SEED_COLOR,
    val themeMode: ThemeMode = ThemeDefaults.DEFAULT_THEME_MODE,
    val paletteStyle: ThemePaletteStyle = ThemeDefaults.DEFAULT_PALETTE_STYLE,
)
