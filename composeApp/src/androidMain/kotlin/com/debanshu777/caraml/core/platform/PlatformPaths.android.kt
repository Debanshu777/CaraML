package com.debanshu777.caraml.core.platform

import android.content.Context
import org.koin.mp.KoinPlatform

actual object PlatformPaths {
    actual fun getNativeLibDir(): String = try {
        (KoinPlatform.getKoin().get<Context>()).applicationInfo.nativeLibraryDir
    } catch (_: Exception) {
        ""
    }

    actual fun getDefaultGpuLayers(): Int = 0
}
