package com.debanshu777.caraml.core.drawer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

enum class CustomDrawerState {
    Opened,
    Closed,
}

fun CustomDrawerState.isOpened(): Boolean = this == CustomDrawerState.Opened

fun CustomDrawerState.opposite(): CustomDrawerState =
    if (isOpened()) CustomDrawerState.Closed else CustomDrawerState.Opened

@Stable
class DrawerController(initialState: CustomDrawerState = CustomDrawerState.Closed) {
    var drawerState by mutableStateOf(initialState)
        private set

    fun open() {
        drawerState = CustomDrawerState.Opened
    }

    fun close() {
        drawerState = CustomDrawerState.Closed
    }

    fun toggle() {
        drawerState = drawerState.opposite()
    }

    fun setState(state: CustomDrawerState) {
        drawerState = state
    }
}

val LocalDrawerController = staticCompositionLocalOf<DrawerController> {
    throw IllegalStateException("No DrawerController provided")
}
