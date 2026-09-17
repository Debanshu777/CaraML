package com.debanshu777.caraml.core.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * App-wide [Shapes] that layer the Prism vocabulary onto Material 3 roles.
 *
 * | Token            | Radius | Typical components                         |
 * |------------------|--------|--------------------------------------------|
 * | `extraSmall`     | 8dp    | Status marks and compact indicators        |
 * | `small`          | 12dp   | Inputs, buttons, selectable controls       |
 * | `medium`         | 18dp   | Command surfaces and meaningful panels     |
 * | `large`          | 24dp   | Sheets and large surfaces                  |
 * | `extraLarge`     | 24dp   | Dialogs, modal bottom sheets               |
 *
 * Use these via `MaterialTheme.shapes.medium` instead of literal
 * `RoundedCornerShape(12.dp)` so radii stay consistent and respond to any
 * future global shape change in one place.
 */
val AppShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(24.dp),
)
