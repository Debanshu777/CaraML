package com.debanshu777.caraml.core.ui.motion

import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.navigation3.ui.LocalNavAnimatedContentScope

internal val LocalModelIdentityNavigation = staticCompositionLocalOf { false }
internal val LocalModelSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** Shared identity draws in the navigation overlay; page copy waits until it has arrived. */
@Composable
internal fun Modifier.modelTransitionContent(): Modifier {
    val scope = LocalModelSharedTransitionScope.current ?: return this
    val navigation = LocalNavAnimatedContentScope.current.transition
    val starting = LocalModelIdentityNavigation.current && navigation.currentState != navigation.targetState
    // Suppress copy before the first shared-animation frame. Restore it atomically when
    // the overlay releases the identity; a parent fade would briefly hide the title too.
    val moving = starting || scope.isTransitionActive
    return graphicsLayer { alpha = if (moving) 0f else 1f }
}

/** A missing scope keeps standalone previews/tests and reduced-motion routes static. */
@Composable
internal fun Modifier.modelIdentityTransition(key: String?, avatar: Boolean = false): Modifier {
    val scope = LocalModelSharedTransitionScope.current ?: return this
    if (key == null || !LocalAuroraMotionPolicy.current.spatialTransitionsEnabled) return this
    val visibility = LocalNavAnimatedContentScope.current
    return with(scope) {
        val state = rememberSharedContentState("$key:${if (avatar) "avatar" else "title"}")
        if (avatar) sharedElement(state, visibility, boundsTransform = { _, _ -> tween(280) })
        else sharedBounds(
            state, visibility,
            // Search includes the owner in its title; details shows the owner separately.
            // Hand off these different strings without drawing both over one another.
            enter = fadeIn(tween(120, delayMillis = 90)),
            exit = fadeOut(tween(90)),
            boundsTransform = { _, _ -> tween(280) },
        )
    }
}
