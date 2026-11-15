package com.debanshu777.caraml.sdk.plugin.diffusion.bridge

import com.debanshu777.caraml.sdk.runtime.DesktopNativeLibraries

internal fun loadDiffusionNativeRuntime(): Boolean = try {
    DesktopNativeLibraries.load("diffusion_runner")
    true
} catch (_: LinkageError) {
    System.err.println("[DiffusionRunner] ERROR: Native diffusion runtime is unavailable")
    false
} catch (_: SecurityException) {
    false
}
