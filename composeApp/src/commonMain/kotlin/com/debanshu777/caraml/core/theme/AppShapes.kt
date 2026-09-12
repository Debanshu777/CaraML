package com.debanshu777.caraml.core.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * App-wide [Shapes] aligned with the Material 3 shape token spec.
 *
 * | Token            | Radius | Typical components                         |
 * |------------------|--------|--------------------------------------------|
 * | `extraSmall`     | 4dp    | Chips, snackbars, small badges             |
 * | `small`          | 12dp   | Text fields, menus                         |
 * | `medium`         | 16dp   | Cards, message bubbles                     |
 * | `large`          | 24dp   | FABs, large surfaces, input bars           |
 * | `extraLarge`     | 28dp   | Dialogs, modal bottom sheets               |
 *
 * Use these via `MaterialTheme.shapes.medium` instead of literal
 * `RoundedCornerShape(12.dp)` so radii stay consistent and respond to any
 * future global shape change in one place.
 */
val AppShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
