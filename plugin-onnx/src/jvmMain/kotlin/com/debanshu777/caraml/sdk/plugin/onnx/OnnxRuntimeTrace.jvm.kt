package com.debanshu777.caraml.sdk.plugin.onnx

internal actual object OnnxRuntimeTrace {
    actual fun isEnabled(): Boolean = false
    actual fun beginSection(name: String) = Unit
    actual fun endSection() = Unit
}
