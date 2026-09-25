package com.debanshu777.caraml

import com.debanshu777.caraml.core.di.initKoin
import com.debanshu777.caraml.core.download.DownloadRuntime
import com.debanshu777.caraml.core.download.IosDownloadScheduler
import com.debanshu777.caraml.core.download.PlatformDownloadScheduler
import org.koin.mp.KoinPlatform

/** Entry point used when iOS wakes or relaunches the app for background URLSession callbacks. */
fun handleIosBackgroundDownloadEvents(identifier: String, completionHandler: () -> Unit) {
    initKoin()
    val koin = KoinPlatform.getKoin()
    val scheduler = koin.get<PlatformDownloadScheduler>() as? IosDownloadScheduler
    if (scheduler == null) {
        completionHandler()
        return
    }
    scheduler.handleBackgroundEvents(identifier, completionHandler)
    koin.get<DownloadRuntime>().start()
}
