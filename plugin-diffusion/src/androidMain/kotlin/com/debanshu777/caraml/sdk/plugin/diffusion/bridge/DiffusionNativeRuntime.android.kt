package com.debanshu777.caraml.sdk.plugin.diffusion.bridge

internal fun loadDiffusionNativeRuntime(): Boolean = try {
    System.loadLibrary("diffusion_runner")
    true
} catch (_: LinkageError) {
    false
} catch (_: SecurityException) {
    false
}
