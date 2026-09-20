package com.debanshu777.caraml.core.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.hct.Hct

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
        Canvas -> scheme.surface
        Recessed -> scheme.surfaceContainerLow
        Pane -> scheme.surfaceContainer
        Floating -> scheme.surfaceContainerHigh
    }
}

internal fun ColorScheme.toAuroraColors(
    isDark: Boolean = surface.luminance() < 0.5f,
    focalSeed: Color = primary,
): AuroraColors {
    val focalSeedHct = Hct.fromInt(focalSeed.toArgb())
    return AuroraColors(
        canvas = surface,
        primaryGlow = if (isDark) primary.copy(alpha = 0.28f) else primaryContainer.copy(alpha = 0.38f),
        secondaryGlow = if (isDark) secondary.copy(alpha = 0.26f) else secondaryContainer.copy(alpha = 0.32f),
        tertiaryGlow = if (isDark) tertiary.copy(alpha = 0.24f) else tertiaryContainer.copy(alpha = 0.34f),
        grainTint = onSurface.copy(alpha = if (isDark) 0.05f else 0.035f),
        edgeVignette = scrim.copy(alpha = if (isDark) 0.18f else 0.06f),
        paneBorder = outlineVariant.copy(alpha = 0.72f),
        commandSurface = surfaceContainer,
        selectedSurface = surfaceContainerHigh,
        divider = outlineVariant.copy(alpha = 0.48f),
        focusPrimary = focalSeed.copy(alpha = 0.78f),
        onFocusPrimary = if (focalSeed.luminance() > 0.179f) Color.Black else Color.White,
        focusSecondary = focalSeedHct.rotate(degrees = 220.0, alpha = 0.74f),
        focusTertiary = focalSeedHct.rotate(degrees = 75.0, alpha = 0.70f),
    )
}

private fun Hct.rotate(degrees: Double, alpha: Float): Color = Color(
    Hct.from(
        hue = (hue + degrees) % 360.0,
        chroma = maxOf(chroma, 48.0),
        tone = tone,
    ).toInt(),
).copy(alpha = alpha)

internal val LocalAuroraColors = staticCompositionLocalOf<AuroraColors?> { null }

val MaterialTheme.auroraColors: AuroraColors
    @Composable
    @ReadOnlyComposable
    get() = LocalAuroraColors.current ?: colorScheme.toAuroraColors()
