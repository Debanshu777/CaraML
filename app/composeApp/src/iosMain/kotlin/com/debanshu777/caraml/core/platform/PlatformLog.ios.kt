package com.debanshu777.caraml.core.platform

import platform.Foundation.NSLog

@PublishedApi
internal actual fun platformLog(
    level: LogLevel,
    tag: String,
    message: String,
    throwable: Throwable?,
) {
    val suffix = throwable?.let { " - $it" } ?: ""
    NSLog("[$tag] ${level.name}: $message$suffix")
}
