package com.debanshu777.caraml.core.platform

actual object PlatformPaths {
    actual fun getNativeLibDir(): String = "."

    actual fun getDefaultGpuLayers(): Int = 99
}
