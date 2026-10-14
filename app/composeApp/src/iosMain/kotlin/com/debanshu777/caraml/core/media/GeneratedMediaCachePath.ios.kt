package com.debanshu777.caraml.core.media

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

@OptIn(ExperimentalForeignApi::class)
actual fun generatedMediaCacheDirectory(): String {
    val path = NSFileManager.defaultManager.URLForDirectory(
        directory = NSCachesDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = true,
        error = null,
    )?.path
    require(!path.isNullOrBlank()) { "Media cache directory is unavailable" }
    return "$path/caraml"
}
