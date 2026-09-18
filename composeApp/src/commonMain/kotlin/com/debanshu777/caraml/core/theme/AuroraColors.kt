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
    val tertiaryGlow: Color,
    val paneBorder: Color,
    val commandSurface: Color,
    val selectedSurface: Color,
    val divider: Color,
    val focusPrimary: Color,
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
): AuroraColors = AuroraColors(
    canvas = surface,
    primaryGlow = if (isDark) primary.copy(alpha = 0.38f) else primaryContainer.copy(alpha = 0.46f),
    tertiaryGlow = if (isDark) tertiary.copy(alpha = 0.28f) else tertiaryContainer.copy(alpha = 0.34f),
    paneBorder = outlineVariant.copy(alpha = 0.72f),
    commandSurface = surfaceContainer,
    selectedSurface = surfaceContainerHigh,
    divider = outlineVariant.copy(alpha = 0.48f),
    focusPrimary = primary.copy(alpha = 0.78f),
    focusTertiary = tertiary.copy(alpha = 0.70f),
)

internal val LocalAuroraColors = staticCompositionLocalOf<AuroraColors?> { null }

val MaterialTheme.auroraColors: AuroraColors
    @Composable
    @ReadOnlyComposable
    get() = LocalAuroraColors.current ?: colorScheme.toAuroraColors()
