package com.debanshu777.caraml.core.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Semantic shape roles for CaraML's Prism design system.
 *
 * Feature code should choose a role by purpose instead of reaching for a Material size name or
 * constructing a new rounded corner. This keeps a status mark visually distinct from a command
 * surface and prevents every region from becoming the same rounded card.
 */
@Immutable
data class PrismShapeRoles(
    val status: Shape,
    val control: Shape,
    val command: Shape,
    val pane: Shape,
    val focal: Shape,
    val modal: Shape,
)

val AppPrismShapes: PrismShapeRoles = PrismShapeRoles(
    status = AppShapes.extraSmall,
    control = AppShapes.small,
    command = AppShapes.medium,
    pane = AppShapes.medium,
    focal = AppShapes.large,
    modal = AppShapes.extraLarge,
)

val MaterialTheme.prismShapes: PrismShapeRoles
    @Composable
    @ReadOnlyComposable
    get() = AppPrismShapes

/** Shared geometry that affects interaction or responsive alignment. */
@Immutable
data class PrismMetrics(
    val minimumTouchTarget: Dp,
    val compactGutter: Dp,
    val expandedGutter: Dp,
)

val AppPrismMetrics = PrismMetrics(
    minimumTouchTarget = 48.dp,
    compactGutter = 16.dp,
    expandedGutter = 24.dp,
)
