package com.debanshu777.caraml.core.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.materialkolor.DynamicMaterialTheme

/**
 * App-wide Material 3 theme.
 *
 * Stack of providers, outer-to-inner:
 *  1. [DynamicMaterialTheme] (materialkolor) — generates an animated
 *     [androidx.compose.material3.ColorScheme] from the user's seed color and
 *     handles palette transitions.
 *  2. [MaterialExpressiveTheme] — re-applies the same colorScheme but layers
 *     on our [AppShapes], [AppTypography], and [AppMotionScheme]
 *     (spring-based component motion). MaterialExpressiveTheme is the only
 *     entry point that takes `motionScheme`.
 *  3. [CompositionLocalProvider] for [LocalSpacing] — global spacing scale.
 *
 * Wrap the entire app content (everything below [com.debanshu777.caraml.App])
 * exactly once.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CaraMLTheme(
    preferences: ThemePreferences,
    content: @Composable () -> Unit,
) {
    val isDark = when (preferences.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    DynamicMaterialTheme(
        seedColor = preferences.seedColor,
        isDark = isDark,
        style = preferences.paletteStyle.toMaterialKolor(),
        animate = true,
    ) {
        MaterialExpressiveTheme(
            colorScheme = MaterialTheme.colorScheme,
            shapes = AppShapes,
            typography = AppTypography,
            motionScheme = AppMotionScheme,
        ) {
            CompositionLocalProvider(LocalSpacing provides Spacing()) {
                content()
            }
        }
    }
}
