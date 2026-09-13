package com.debanshu777.caraml.core.ui.motion

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

@Immutable
data class AuroraMotionPolicy(
    val durationScale: Float,
    val spatialTransitionsEnabled: Boolean,
    val pulseEnabled: Boolean,
    val shapeMorphEnabled: Boolean,
    val opacityDurationMillis: Int,
    val peerTransitionMillis: Int,
    val detailEnterMillis: Int,
    val hierarchicalPopEnterMillis: Int,
    val exitMillis: Int,
    val generationPulseMillis: Int,
)

fun auroraMotionPolicy(durationScale: Float): AuroraMotionPolicy {
    val reducedMotion = durationScale <= 0f
    return AuroraMotionPolicy(
        durationScale = durationScale,
        spatialTransitionsEnabled = !reducedMotion,
        pulseEnabled = !reducedMotion,
        shapeMorphEnabled = !reducedMotion,
        opacityDurationMillis = if (reducedMotion) 100 else 220,
        peerTransitionMillis = 220,
        detailEnterMillis = 300,
        hierarchicalPopEnterMillis = 250,
        exitMillis = 200,
        generationPulseMillis = 1600,
    )
}

val LocalAuroraMotionPolicy = staticCompositionLocalOf {
    auroraMotionPolicy(durationScale = 1f)
}
