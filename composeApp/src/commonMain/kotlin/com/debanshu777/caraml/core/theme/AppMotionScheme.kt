package com.debanshu777.caraml.core.theme

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme

/**
 * App-wide MotionScheme for Material 3 Expressive components.
 *
 * `MotionScheme.expressive()` enables spring-based physics on supported
 * components (FAB scale, button press, navigation transitions, list reorder).
 * Wired into [CaraMLTheme] via [androidx.compose.material3.MaterialExpressiveTheme].
 *
 * Note: still experimental in Material 3 1.10.0-alpha05 — the `@OptIn` carries
 * over to anyone reading this constant.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
val AppMotionScheme: MotionScheme = MotionScheme.expressive()
