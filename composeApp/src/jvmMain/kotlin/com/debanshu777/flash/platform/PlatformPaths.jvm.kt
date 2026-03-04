package com.debanshu777.flash.platform

import java.io.File

actual object PlatformPaths {
    actual fun getNativeLibDir(): String {
        val fromProp = System.getProperty("flash.native.lib.dir", "").trim()
        if (fromProp.isNotEmpty()) return fromProp
        // Fallback: use first path from java.library.path (set by Gradle run task)
        val libPath = System.getProperty("java.library.path", "")
        return libPath.split(File.pathSeparator).map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""
    }

    actual fun getDefaultGpuLayers(): Int = 0
}
