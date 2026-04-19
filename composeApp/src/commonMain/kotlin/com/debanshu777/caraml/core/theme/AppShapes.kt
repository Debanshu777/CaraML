package com.debanshu777.caraml.core.theme

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * App-wide [Shapes] aligned with the Material 3 shape token spec.
 *
 * | Token            | Radius | Typical components                         |
 * |------------------|--------|--------------------------------------------|
 * | `extraSmall`     | 4dp    | Chips, snackbars, small badges             |
 * | `small`          | 8dp    | Text fields, menus                         |
 * | `medium`         | 12dp   | Cards, message bubbles                     |
 * | `large`          | 16dp   | FABs, large surfaces, input bars           |
 * | `extraLarge`     | 28dp   | Dialogs, modal bottom sheets               |
 *
 * Use these via `MaterialTheme.shapes.medium` instead of literal
 * `RoundedCornerShape(12.dp)` so radii stay consistent and respond to any
 * future global shape change in one place.
 */
val AppShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(CornerSize(4.dp)),
    small = RoundedCornerShape(CornerSize(8.dp)),
    medium = RoundedCornerShape(CornerSize(12.dp)),
    large = RoundedCornerShape(CornerSize(16.dp)),
    extraLarge = RoundedCornerShape(CornerSize(28.dp)),
)
