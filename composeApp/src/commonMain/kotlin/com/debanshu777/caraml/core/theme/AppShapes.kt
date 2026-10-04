package com.debanshu777.caraml.core.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Corner radius tokens named by their value in dp. */
@Immutable
data class CornerRadii(
    val radius8: Dp = 8.dp,
    val radius12: Dp = 12.dp,
    val radius18: Dp = 18.dp,
    val radius24: Dp = 24.dp,
)

val AppCornerRadii = CornerRadii()

/** One-sided rounding for the navigation drawer panel. */
val AppDrawerPanelShape = RoundedCornerShape(
    topEnd = AppCornerRadii.radius24,
    bottomEnd = AppCornerRadii.radius24,
)

/** Material shape roles backed by the shared corner scale. */
val AppShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(AppCornerRadii.radius8),
    small = RoundedCornerShape(AppCornerRadii.radius12),
    medium = RoundedCornerShape(AppCornerRadii.radius18),
    large = RoundedCornerShape(AppCornerRadii.radius24),
    extraLarge = RoundedCornerShape(AppCornerRadii.radius24),
)
