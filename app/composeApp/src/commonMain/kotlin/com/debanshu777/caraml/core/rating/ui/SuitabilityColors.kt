package com.debanshu777.caraml.core.rating.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.core.rating.SuitabilityRating
import com.debanshu777.caraml.core.recommendation.RecommendationCategory

@Composable
@ReadOnlyComposable
fun RecommendationCategory.containerColor(): Color = when (this) {
    RecommendationCategory.RECOMMENDED -> AppTheme.colors.primaryContainer
    RecommendationCategory.USABLE -> AppTheme.colors.tertiaryContainer
    RecommendationCategory.RISKY -> AppTheme.colors.secondaryContainer
    RecommendationCategory.NOT_SUITABLE, RecommendationCategory.INCOMPATIBLE -> AppTheme.colors.errorContainer
    RecommendationCategory.NEEDS_INFORMATION -> AppTheme.colors.surfaceVariant
}

@Composable
@ReadOnlyComposable
fun RecommendationCategory.onContainerColor(): Color = when (this) {
    RecommendationCategory.RECOMMENDED -> AppTheme.colors.onPrimaryContainer
    RecommendationCategory.USABLE -> AppTheme.colors.onTertiaryContainer
    RecommendationCategory.RISKY -> AppTheme.colors.onSecondaryContainer
    RecommendationCategory.NOT_SUITABLE, RecommendationCategory.INCOMPATIBLE -> AppTheme.colors.onErrorContainer
    RecommendationCategory.NEEDS_INFORMATION -> AppTheme.colors.onSurfaceVariant
}

/**
 * Theme-driven color mapping for [SuitabilityRating].
 *
 * Intentionally avoids hardcoded hex values — every tier maps to a Material 3
 * color role so light/dark mode + the app's dynamic theme (materialKolor)
 * stay coherent without extra work.
 */
@Composable
@ReadOnlyComposable
fun SuitabilityRating.foregroundColor(): Color = when (this) {
    SuitabilityRating.BEST -> AppTheme.colors.primary
    SuitabilityRating.GOOD -> AppTheme.colors.tertiary
    SuitabilityRating.AVERAGE -> AppTheme.colors.secondary
    SuitabilityRating.POOR -> AppTheme.colors.error
    SuitabilityRating.UNKNOWN -> AppTheme.colors.outline
}

/** Container background for the chip (matches the foreground role). */
@Composable
@ReadOnlyComposable
fun SuitabilityRating.containerColor(): Color = when (this) {
    SuitabilityRating.BEST -> AppTheme.colors.primaryContainer
    SuitabilityRating.GOOD -> AppTheme.colors.tertiaryContainer
    SuitabilityRating.AVERAGE -> AppTheme.colors.secondaryContainer
    SuitabilityRating.POOR -> AppTheme.colors.errorContainer
    SuitabilityRating.UNKNOWN -> AppTheme.colors.surfaceVariant
}

/** Text/icon tint when drawn on top of [containerColor]. */
@Composable
@ReadOnlyComposable
fun SuitabilityRating.onContainerColor(): Color = when (this) {
    SuitabilityRating.BEST -> AppTheme.colors.onPrimaryContainer
    SuitabilityRating.GOOD -> AppTheme.colors.onTertiaryContainer
    SuitabilityRating.AVERAGE -> AppTheme.colors.onSecondaryContainer
    SuitabilityRating.POOR -> AppTheme.colors.onErrorContainer
    SuitabilityRating.UNKNOWN -> AppTheme.colors.onSurfaceVariant
}
