package com.debanshu777.caraml.core.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.debanshu777.caraml.core.ui.motion.LocalAuroraMotionPolicy

/** Single access point for CaraML design tokens, following the MintTheme usage pattern. */
object AppTheme {
    val spacing: Spacing = Spacing()
    val dimensions: AppDimensions = AppDimensions()
    val effects: AppEffects = AppEffects()
    val shapes = AppShapes
    val buttons = AppButtonTokens

    val typography: AppTypeScale
        @Composable
        @ReadOnlyComposable
        get() = LocalAppTypeScale.current ?: AppTypeScale(MaterialTheme.typography)

    val brandColors: AppBrandColors
        @Composable
        @ReadOnlyComposable
        get() = LocalAppBrandColors.current

    /** Readable seed-colored text/icons on neutral surfaces; use colors.primary for fills. */
    val actionColor: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalAppActionColor.current ?: actionColor(
            seed = MaterialTheme.colorScheme.primary,
            isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f,
        )

    val softEffects: Boolean
        @Composable
        @ReadOnlyComposable
        get() = LocalSoftEffects.current

    val motion: AppMotion
        @Composable
        @ReadOnlyComposable
        get() = AppMotion(LocalAuroraMotionPolicy.current)

    val colors
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.colorScheme

    val auroraColors: AuroraColors
        @Composable
        @ReadOnlyComposable
        get() = currentAuroraColors
}
