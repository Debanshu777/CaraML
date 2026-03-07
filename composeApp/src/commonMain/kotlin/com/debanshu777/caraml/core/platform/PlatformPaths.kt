package com.debanshu777.caraml.core.platform

expect object PlatformPaths {
    fun getNativeLibDir(): String
    fun getDefaultGpuLayers(): Int
}
