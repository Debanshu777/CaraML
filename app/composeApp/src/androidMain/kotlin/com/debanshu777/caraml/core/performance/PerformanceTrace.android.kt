package com.debanshu777.caraml.core.performance

import android.os.Build
import android.os.Trace
import java.util.concurrent.atomic.AtomicInteger

actual object PerformanceTrace {
    private val cookies = AtomicInteger()
    actual fun isEnabled(): Boolean = Build.VERSION.SDK_INT >= 29 && Trace.isEnabled()
    actual fun nextCookie(): Int = cookies.incrementAndGet()
    actual fun begin(name: String, cookie: Int) {
        if (Build.VERSION.SDK_INT >= 29) Trace.beginAsyncSection(name, cookie)
    }
    actual fun end(name: String, cookie: Int) {
        if (Build.VERSION.SDK_INT >= 29) Trace.endAsyncSection(name, cookie)
    }
}
