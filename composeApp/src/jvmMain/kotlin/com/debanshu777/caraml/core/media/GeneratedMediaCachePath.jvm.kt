package com.debanshu777.caraml.core.media

import java.io.File

actual fun generatedMediaCacheDirectory(): String {
    val temporaryRoot = System.getProperty("java.io.tmpdir")
        ?.takeIf { it.isNotBlank() }
        ?: throw IllegalStateException("Temporary directory is unavailable")
    return File(temporaryRoot, "caraml-cache").absolutePath
}
