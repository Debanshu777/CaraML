package com.debanshu777.caraml.core.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Immutable
import com.debanshu777.caraml.core.ui.motion.AuroraMotionPolicy

/**
 * App-wide MotionScheme for Material 3 Expressive components.
 *
 * `MotionScheme.expressive()` enables spring-based physics on supported
 * components (FAB scale, button press, navigation transitions, list reorder).
 * Wired into [CaraMLTheme] via [androidx.compose.material3.MaterialExpressiveTheme].
 *
 * Note: still experimental in Material 3 1.10.0-alpha05 — the `@OptIn` carries
 * over to anyone reading this constant.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
val AppMotionScheme: MotionScheme = MotionScheme.expressive()

/** Shared timings keep reveal, touch feedback and character state changes in one language. */
object AppMotionTokens {
    const val sidebarRevealMillis = 540
    val sidebarRevealEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
    const val reducedOpacityMillis = 90
    const val statusMillis = 240
    const val pressScale = 0.96f
}

/** Specs are selected from the effective system/user policy at the composition call site. */
@Immutable
class AppMotion internal constructor(private val policy: AuroraMotionPolicy) {
    val spatialTransitionsEnabled: Boolean get() = policy.spatialTransitionsEnabled
    val statusDurationMillis: Int get() = if (spatialTransitionsEnabled) AppMotionTokens.statusMillis else AppMotionTokens.reducedOpacityMillis

    fun <T> drawerRevealSpec(): FiniteAnimationSpec<T> = if (spatialTransitionsEnabled) {
        tween(AppMotionTokens.sidebarRevealMillis, easing = AppMotionTokens.sidebarRevealEasing)
    } else {
        snap()
    }

    fun <T> pressSpec(): FiniteAnimationSpec<T> = if (spatialTransitionsEnabled) {
        spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium)
    } else {
        snap()
    }

    fun <T> statusSpec(): FiniteAnimationSpec<T> = if (spatialTransitionsEnabled) {
        spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessLow)
    } else {
        snap()
    }

    fun <T> opacitySpec(): FiniteAnimationSpec<T> = tween(policy.opacityDurationMillis)
}

/** Also suppress Material component springs when the in-app reduced motion option is enabled. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal fun appMotionScheme(policy: AuroraMotionPolicy): MotionScheme =
    if (policy.spatialTransitionsEnabled) AppMotionScheme else ReducedAppMotionScheme

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private object ReducedAppMotionScheme : MotionScheme {
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = tween(AppMotionTokens.reducedOpacityMillis)
    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = tween(AppMotionTokens.reducedOpacityMillis)
    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = tween(AppMotionTokens.reducedOpacityMillis)
}
