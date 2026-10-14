package com.debanshu777.caraml

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.debanshu777.caraml.core.di.initKoin
import com.debanshu777.caraml.core.download.DownloadRuntime
import kotlinx.coroutines.runBlocking
import org.koin.mp.KoinPlatform

fun main() {
    initKoin()
    val downloadRuntime = KoinPlatform.getKoin().get<DownloadRuntime>().also { it.start() }
    application {
        Window(
            onCloseRequest = {
                runBlocking { downloadRuntime.close() }
                exitApplication()
            },
            title = "CaraML",
        ) {
            App()
        }
    }
}
