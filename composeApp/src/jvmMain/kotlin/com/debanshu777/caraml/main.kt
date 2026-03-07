package com.debanshu777.caraml

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.debanshu777.caraml.core.di.initKoin

fun main() {
    initKoin()
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "CaraML",
        ) {
            App()
        }
    }
}