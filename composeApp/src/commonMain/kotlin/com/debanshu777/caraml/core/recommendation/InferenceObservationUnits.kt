package com.debanshu777.caraml.core.recommendation

/** Returns comparable diffusion denoising units, or null for invalid/overflowing workloads. */
internal fun diffusionObservationUnits(steps: Int, frames: Int): Int? {
    if (steps <= 0 || frames <= 0 || steps > Int.MAX_VALUE / frames) return null
    return steps * frames
}
