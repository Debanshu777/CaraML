package com.debanshu777.caraml.core.platform

@PublishedApi
internal actual fun platformLog(
    level: LogLevel,
    tag: String,
    message: String,
    throwable: Throwable?,
) {
    val formatted = "[$tag] ${level.name}: $message"
    when (level) {
        LogLevel.DEBUG, LogLevel.INFO -> println(formatted)
        LogLevel.WARN, LogLevel.ERROR -> {
            System.err.println(formatted)
            throwable?.printStackTrace(System.err)
        }
    }
}
