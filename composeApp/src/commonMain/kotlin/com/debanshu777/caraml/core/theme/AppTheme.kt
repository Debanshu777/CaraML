package com.debanshu777.caraml.core.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable

/** Single access point for CaraML design tokens, following the MintTheme usage pattern. */
object AppTheme {
    val spacing: Spacing = Spacing()
    val dimensions: AppDimensions = AppDimensions()
    val effects: AppEffects = AppEffects()
    val typography: AppTypeScale = AppTypeScale()
    val shapes = AppShapes

    val colors
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.colorScheme

    val auroraColors: AuroraColors
        @Composable
        @ReadOnlyComposable
        get() = currentAuroraColors
}
