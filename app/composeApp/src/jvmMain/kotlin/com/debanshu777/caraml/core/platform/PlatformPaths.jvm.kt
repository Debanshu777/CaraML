package com.debanshu777.caraml.core.platform

import java.io.File
import com.debanshu777.caraml.sdk.runtime.DesktopNativeLibraries

actual object PlatformPaths {
    actual fun getNativeLibDir(): String {
        DesktopNativeLibraries.directory()?.let { return it }
        val libPath = System.getProperty("java.library.path", "")
        return libPath.split(File.pathSeparator).map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""
    }
}
