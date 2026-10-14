package com.debanshu777.caraml.core.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

@Immutable
data class AuroraColors(
    val canvas: Color,
    val primaryGlow: Color,
    val secondaryGlow: Color,
    val tertiaryGlow: Color,
    val grainTint: Color,
    val edgeVignette: Color,
    val paneBorder: Color,
    val commandSurface: Color,
    val selectedSurface: Color,
    val divider: Color,
    val focusPrimary: Color,
    val onFocusPrimary: Color,
    val focusSecondary: Color,
    val focusTertiary: Color,
)

enum class AuroraSurfaceLevel(val containerAlpha: Float) {
    Canvas(1f),
    Recessed(0.76f),
    Pane(0.82f),
    Floating(0.94f),
    ;

    fun containerColor(scheme: ColorScheme): Color = when (this) {
        Canvas -> scheme.background
        Recessed -> scheme.surfaceContainerLow
        Pane -> scheme.surfaceContainer
        Floating -> scheme.surfaceContainerHigh
    }
}

internal fun ColorScheme.toAuroraColors(
    isDark: Boolean = surface.luminance() < 0.5f,
    focalSeed: Color = primary,
): AuroraColors {
    val brand = AppBrandColors(accent = focalSeed)
    return AuroraColors(
        canvas = background,
        primaryGlow = brand.accent.copy(alpha = 0.40f),
        secondaryGlow = brand.lilac.copy(alpha = 0.40f),
        tertiaryGlow = brand.mint.copy(alpha = 0.32f),
        grainTint = onSurface.copy(alpha = 0.022f),
        edgeVignette = if (isDark) Color(0x20000000) else Color(0x0A242020),
        paneBorder = outlineVariant,
        commandSurface = surface,
        selectedSurface = surfaceContainerHigh,
        divider = outlineVariant,
        focusPrimary = focalSeed.copy(alpha = 0.78f),
        onFocusPrimary = if (focalSeed.luminance() > 0.179f) Color.Black else Color.White,
        focusSecondary = brand.lilac.copy(alpha = 0.74f),
        focusTertiary = brand.mint.copy(alpha = 0.70f),
    )
}

internal val LocalAuroraColors = staticCompositionLocalOf<AuroraColors?> { null }

internal val currentAuroraColors: AuroraColors
    @Composable
    @ReadOnlyComposable
    get() = LocalAuroraColors.current ?: MaterialTheme.colorScheme.toAuroraColors()
