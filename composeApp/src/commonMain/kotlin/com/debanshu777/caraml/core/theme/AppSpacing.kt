package com.debanshu777.caraml.core.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Spacing tokens named by their value in dp. */
@Immutable
data class Spacing(
    val spacing2: Dp = 2.dp,
    val spacing4: Dp = 4.dp,
    val spacing8: Dp = 8.dp,
    val spacing12: Dp = 12.dp,
    val spacing16: Dp = 16.dp,
    val spacing24: Dp = 24.dp,
    val spacing32: Dp = 32.dp,
    val spacing48: Dp = 48.dp,
    val spacing64: Dp = 64.dp,
)
