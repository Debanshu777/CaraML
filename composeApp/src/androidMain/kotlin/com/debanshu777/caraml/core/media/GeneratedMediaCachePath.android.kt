package com.debanshu777.caraml.core.media

import android.content.Context
import org.koin.mp.KoinPlatform

actual fun generatedMediaCacheDirectory(): String =
    KoinPlatform.getKoin().get<Context>().cacheDir.resolve("caraml").absolutePath
