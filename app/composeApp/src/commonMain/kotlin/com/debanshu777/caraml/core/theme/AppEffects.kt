package com.debanshu777.caraml.core.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Alpha values used by shared surfaces and Aurora effects. */
@Immutable
data class AppEffects(
    val chromeBlur: Dp = 20.dp,
    val chromeTint: Float = 0.28f,
    val chromeFade: Dp = 32.dp,
    val opaque: Float = 1f,
    val workspaceGlyphSurface: Float = 0.78f,
    val contextStripSurface: Float = 0.54f,
    val decisionSurface: Float = 0.72f,
    val modalScrim: Float = 0.44f,
    val commandFocus: Float = 0.20f,
    val focalPrimary: Float = 0.36f,
    val focalSecondary: Float = 0.38f,
    val focalTertiary: Float = 0.32f,
    val focalVignette: Float = 0.65f,
    val grainBase: Color = Color.White,
)
