package com.debanshu777.caraml.core.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class AuroraColors(
    val canvas: Color,
    val primaryGlow: Color,
    val tertiaryGlow: Color,
    val paneBorder: Color,
)

enum class AuroraSurfaceLevel {
    Canvas,
    Recessed,
    Pane,
    Floating,
    ;

    fun containerColor(scheme: ColorScheme): Color = when (this) {
        Canvas -> scheme.surface
        Recessed -> scheme.surfaceContainerLow
        Pane -> scheme.surfaceContainer
        Floating -> scheme.surfaceContainerHigh
    }
}

internal fun ColorScheme.toAuroraColors(): AuroraColors = AuroraColors(
    canvas = surface,
    primaryGlow = primaryContainer.copy(alpha = 0.34f),
    tertiaryGlow = tertiaryContainer.copy(alpha = 0.22f),
    paneBorder = outlineVariant.copy(alpha = 0.72f),
)

internal val LocalAuroraColors = staticCompositionLocalOf<AuroraColors?> { null }

val MaterialTheme.auroraColors: AuroraColors
    @Composable
    @ReadOnlyComposable
    get() = LocalAuroraColors.current ?: colorScheme.toAuroraColors()
