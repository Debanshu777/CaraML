package com.debanshu777.caraml.sdk.plugin.llama.bridge

import com.debanshu777.caraml.sdk.runtime.DesktopNativeLibraries

internal fun loadLlamaNativeRuntime(): Boolean = try {
    DesktopNativeLibraries.load("llama_runner")
    true
} catch (_: LinkageError) {
    System.err.println("[Runner] ERROR: Native language-model runtime is unavailable")
    false
} catch (_: SecurityException) {
    false
}
