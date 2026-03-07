package com.debanshu777.caraml.core.platform

import java.io.File

actual object PlatformPaths {
    actual fun getNativeLibDir(): String {
        val fromProp = System.getProperty("caraml.native.lib.dir", "").trim()
        if (fromProp.isNotEmpty()) return fromProp
        val libPath = System.getProperty("java.library.path", "")
        return libPath.split(File.pathSeparator).map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""
    }

    actual fun getDefaultGpuLayers(): Int = 0
}
