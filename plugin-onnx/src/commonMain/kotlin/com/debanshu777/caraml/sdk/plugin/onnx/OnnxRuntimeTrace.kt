package com.debanshu777.caraml.sdk.plugin.onnx

/** Android system tracing only; the other runtimes intentionally remain no-ops. */
internal expect object OnnxRuntimeTrace {
    fun isEnabled(): Boolean
    fun beginSection(name: String)
    fun endSection()
}

/** Blocking work only: Android synchronous sections must stay on their starting thread. */
internal inline fun <T> traceOnnxRuntime(enabled: Boolean, name: String, block: () -> T): T {
    if (!enabled) return block()
    OnnxRuntimeTrace.beginSection(name)
    return try {
        block()
    } finally {
        OnnxRuntimeTrace.endSection()
    }
}

/** First two token-producing iterations, then tokens 8, 32, 128, 512 and 2048. */
internal fun isOnnxTokenTraceSample(generatedCount: Int): Boolean = when (generatedCount) {
    0, 1, 7, 31, 127, 511, 2047 -> true
    else -> false
}
