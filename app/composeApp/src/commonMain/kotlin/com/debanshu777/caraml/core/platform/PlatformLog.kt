package com.debanshu777.caraml.core.platform

enum class LogLevel(val priority: Int) {
    DEBUG(0),
    INFO(1),
    WARN(2),
    ERROR(3),
}

@PublishedApi
internal expect fun platformLog(
    level: LogLevel,
    tag: String,
    message: String,
    throwable: Throwable?,
)

object AppLogger {
    var minLevel: LogLevel = LogLevel.INFO

    inline fun d(tag: String, message: () -> String) {
        if (minLevel.priority <= LogLevel.DEBUG.priority) {
            platformLog(LogLevel.DEBUG, tag, message(), null)
        }
    }

    inline fun i(tag: String, message: () -> String) {
        if (minLevel.priority <= LogLevel.INFO.priority) {
            platformLog(LogLevel.INFO, tag, message(), null)
        }
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (minLevel.priority <= LogLevel.WARN.priority) {
            platformLog(LogLevel.WARN, tag, message, throwable)
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        platformLog(LogLevel.ERROR, tag, message, throwable)
    }
}
