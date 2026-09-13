package com.debanshu777.caraml.core.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.MotionDurationScale
import com.debanshu777.caraml.core.ui.motion.LocalAuroraMotionPolicy
import com.debanshu777.caraml.core.ui.motion.auroraMotionPolicy
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
    onEffectiveDarkThemeChanged: (Boolean) -> Unit = {},
    content: @Composable () -> Unit,
) {
    val isDark = when (preferences.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val currentOnEffectiveDarkThemeChanged by rememberUpdatedState(onEffectiveDarkThemeChanged)
    LaunchedEffect(isDark) {
        currentOnEffectiveDarkThemeChanged(isDark)
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
            val scheme = MaterialTheme.colorScheme
            val auroraColors = remember(scheme, isDark) { scheme.toAuroraColors(isDark) }
            val durationScale = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
            val motionPolicy = auroraMotionPolicy(durationScale)
            CompositionLocalProvider(
                LocalSpacing provides Spacing(),
                LocalAuroraColors provides auroraColors,
                LocalAuroraMotionPolicy provides motionPolicy,
            ) {
                content()
            }
        }
    }
}
