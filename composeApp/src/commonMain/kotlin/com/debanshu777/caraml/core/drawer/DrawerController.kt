package com.debanshu777.caraml.core.drawer

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

@Stable
class DrawerController(initiallyOpen: Boolean = false) {
    var isOpen by mutableStateOf(initiallyOpen)
        private set

    fun open() {
        isOpen = true
    }

    fun close() {
        isOpen = false
    }

    fun toggle() {
        isOpen = !isOpen
    }
}

val LocalDrawerController = staticCompositionLocalOf<DrawerController> {
    throw IllegalStateException("No DrawerController provided")
}

/** Present only when the current primary destination owns a compact modal-sidebar trigger. */
val LocalNavigationMenuAction = staticCompositionLocalOf<(() -> Unit)?> { null }
