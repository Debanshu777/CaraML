package com.debanshu777.caraml.core.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Densities of negative space available across the app.
 *
 * Steps follow a 4dp baseline grid (Material 3 spacing recommendation):
 * `xxs` = 2dp, `xs` = 4dp, `s` = 8dp, `m` = 12dp, `l` = 16dp, `xl` = 24dp, `xxl` = 32dp.
 *
 * Access via `LocalSpacing.current.l` instead of bare `16.dp` so the entire
 * app can be made denser/looser with one edit. Provided by [CaraMLTheme].
 */
@Immutable
data class Spacing(
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val s: Dp = 8.dp,
    val m: Dp = 12.dp,
    val l: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
)

/**
 * CompositionLocal so any composable below [CaraMLTheme] can read the active
 * spacing scale. `staticCompositionLocalOf` is correct here — the value never
 * changes during a session, so we don't pay the cost of state observation.
 */
val LocalSpacing = staticCompositionLocalOf { Spacing() }
