package com.debanshu777.caraml.core.performance

actual object PerformanceTrace {
    actual fun isEnabled(): Boolean = false
    actual fun nextCookie(): Int = 0
    actual fun begin(name: String, cookie: Int) = Unit
    actual fun end(name: String, cookie: Int) = Unit
}
