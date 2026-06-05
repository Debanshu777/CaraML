package com.debanshu777.caraml.core.rating.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.debanshu777.caraml.core.rating.SuitabilityRating

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
    SuitabilityRating.BEST -> MaterialTheme.colorScheme.primary
    SuitabilityRating.GOOD -> MaterialTheme.colorScheme.tertiary
    SuitabilityRating.AVERAGE -> MaterialTheme.colorScheme.secondary
    SuitabilityRating.POOR -> MaterialTheme.colorScheme.error
    SuitabilityRating.UNKNOWN -> MaterialTheme.colorScheme.outline
}

/** Container background for the chip (matches the foreground role). */
@Composable
@ReadOnlyComposable
fun SuitabilityRating.containerColor(): Color = when (this) {
    SuitabilityRating.BEST -> MaterialTheme.colorScheme.primaryContainer
    SuitabilityRating.GOOD -> MaterialTheme.colorScheme.tertiaryContainer
    SuitabilityRating.AVERAGE -> MaterialTheme.colorScheme.secondaryContainer
    SuitabilityRating.POOR -> MaterialTheme.colorScheme.errorContainer
    SuitabilityRating.UNKNOWN -> MaterialTheme.colorScheme.surfaceVariant
}

/** Text/icon tint when drawn on top of [containerColor]. */
@Composable
@ReadOnlyComposable
fun SuitabilityRating.onContainerColor(): Color = when (this) {
    SuitabilityRating.BEST -> MaterialTheme.colorScheme.onPrimaryContainer
    SuitabilityRating.GOOD -> MaterialTheme.colorScheme.onTertiaryContainer
    SuitabilityRating.AVERAGE -> MaterialTheme.colorScheme.onSecondaryContainer
    SuitabilityRating.POOR -> MaterialTheme.colorScheme.onErrorContainer
    SuitabilityRating.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
}
