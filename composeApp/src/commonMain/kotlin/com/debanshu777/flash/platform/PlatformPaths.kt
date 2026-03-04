package com.debanshu777.flash.platform

expect object PlatformPaths {
    fun getNativeLibDir(): String
    fun getDefaultGpuLayers(): Int
}
