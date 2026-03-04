package com.debanshu777.flash.platform

actual object PlatformPaths {
    actual fun getNativeLibDir(): String = "."

    actual fun getDefaultGpuLayers(): Int = 99
}
