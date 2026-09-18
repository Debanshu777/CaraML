package com.debanshu777.caraml.core.drawer

import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf

/** Source-compatible bridge for callers that still provide the legacy menu callback. */
@Stable
class DrawerController {
    fun toggle() = Unit
}

val LocalDrawerController = staticCompositionLocalOf<DrawerController> {
    throw IllegalStateException("No DrawerController provided")
}
