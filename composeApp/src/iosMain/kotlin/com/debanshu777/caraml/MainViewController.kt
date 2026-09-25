package com.debanshu777.caraml

import androidx.compose.ui.window.ComposeUIViewController
import com.debanshu777.caraml.core.di.initKoin
import com.debanshu777.caraml.core.download.DownloadRuntime
import org.koin.mp.KoinPlatform
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController {
    initKoin()
    KoinPlatform.getKoin().get<DownloadRuntime>().start()
    return ComposeUIViewController { App() }
}
