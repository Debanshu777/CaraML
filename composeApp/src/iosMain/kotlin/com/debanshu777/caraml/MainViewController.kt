package com.debanshu777.caraml

import androidx.compose.ui.window.ComposeUIViewController
import com.debanshu777.caraml.core.di.initKoin
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController {
    initKoin()
    return ComposeUIViewController { App() }
}