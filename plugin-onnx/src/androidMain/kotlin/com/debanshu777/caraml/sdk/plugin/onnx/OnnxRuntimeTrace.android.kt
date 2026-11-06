package com.debanshu777.caraml.sdk.plugin.onnx

import android.os.Build
import android.os.Trace

internal actual object OnnxRuntimeTrace {
    actual fun isEnabled(): Boolean = Build.VERSION.SDK_INT >= 29 && Trace.isEnabled()
    actual fun beginSection(name: String) = Trace.beginSection(name)
    actual fun endSection() = Trace.endSection()
}
