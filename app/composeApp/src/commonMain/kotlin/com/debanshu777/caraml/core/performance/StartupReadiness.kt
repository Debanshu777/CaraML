package com.debanshu777.caraml.core.performance

import androidx.compose.runtime.staticCompositionLocalOf

/** The shell is usable after persisted initial data and abandoned-load recovery settle.
 * Model loading and first-token readiness are measured separately by PerformanceTrace.
 */
val LocalAppStartupReporter = staticCompositionLocalOf<(Boolean) -> Unit> { {} }
val LocalChatInventoryReporter = staticCompositionLocalOf<(Boolean) -> Unit> { {} }
