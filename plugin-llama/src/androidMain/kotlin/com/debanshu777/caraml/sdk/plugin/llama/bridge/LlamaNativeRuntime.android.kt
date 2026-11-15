package com.debanshu777.caraml.sdk.plugin.llama.bridge

internal fun loadLlamaNativeRuntime(): Boolean = try {
    System.loadLibrary("llama_runner")
    true
} catch (_: LinkageError) {
    false
} catch (_: SecurityException) {
    false
}
