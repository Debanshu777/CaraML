package com.debanshu777.caraml.core.drawer

import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.debanshu777.caraml.core.ui.motion.LocalAuroraMotionPolicy

data class DrawerAnimationConfig(
    val contentScaleWhenOpen: Float = 0.9f,
    val contentOffsetFraction: Float = 0.80f,
    val animationDurationMs: Int = 300,
)

private const val EdgeSwipeThresholdPx = 48f
private const val SwipeDragThresholdPx = 100f

@Composable
fun AnimatedDrawerScaffold(
    drawerState: CustomDrawerState,
    onDrawerStateChange: (CustomDrawerState) -> Unit,
    gestureEnabled: Boolean,
    drawerContent: @Composable () -> Unit,
    content: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    animationConfig: DrawerAnimationConfig = DrawerAnimationConfig(),
) {
    val density = LocalDensity.current
    val motionPolicy = LocalAuroraMotionPolicy.current
    val isOpened = drawerState.isOpened()

    BoxWithConstraints(
        modifier = modifier.fillMaxSize()
    ) {
        val maxWidthPx = constraints.maxWidth.toFloat()
        val offsetValuePx = maxWidthPx * animationConfig.contentOffsetFraction
        val offsetValueDp = (offsetValuePx / density.density).dp
        val transition = updateTransition(
            targetState = isOpened,
            label = "DrawerTransition",
        )
        val animatedOffset by transition.animateDp(
            transitionSpec = {
                spring(dampingRatio = 0.82f, stiffness = 500f)
            },
            label = "DrawerOffset",
        ) { opened ->
            if (motionPolicy.spatialTransitionsEnabled && opened) offsetValueDp else 0.dp
        }
        val animatedScale by transition.animateFloat(
            transitionSpec = {
                spring(dampingRatio = 0.82f, stiffness = 500f)
            },
            label = "DrawerScale",
        ) { opened ->
            if (motionPolicy.spatialTransitionsEnabled && opened) {
                animationConfig.contentScaleWhenOpen
            } else {
                1f
            }
        }
        val scrimAlpha by transition.animateFloat(
            transitionSpec = {
                if (motionPolicy.spatialTransitionsEnabled) {
                    spring(dampingRatio = 0.82f, stiffness = 500f)
                } else {
                    tween(motionPolicy.opacityDurationMillis)
                }
            },
            label = "DrawerScrim",
        ) { opened ->
            if (opened) 0.20f else 0f
        }
        val animatedCornerSize by transition.animateDp(
            transitionSpec = {
                spring(dampingRatio = 0.82f, stiffness = 500f)
            },
            label = "DrawerCorner",
        ) { opened ->
            if (motionPolicy.shapeMorphEnabled && opened) 24.dp else 0.dp
        }
        val drawerAlpha by transition.animateFloat(
            transitionSpec = {
                if (motionPolicy.spatialTransitionsEnabled) {
                    spring(dampingRatio = 0.82f, stiffness = 500f)
                } else {
                    tween(motionPolicy.opacityDurationMillis)
                }
            },
            label = "DrawerOpacity",
        ) { opened ->
            if (motionPolicy.spatialTransitionsEnabled || opened) 1f else 0f
        }
        val contentShape = RoundedCornerShape(animatedCornerSize)

        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .alpha(drawerAlpha.coerceIn(0f, 1f))
                .zIndex(
                    if (
                        !motionPolicy.spatialTransitionsEnabled &&
                        (transition.currentState || transition.targetState)
                    ) {
                        2f
                    } else {
                        0f
                    },
                )
        ) {
            drawerContent()
        }

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxSize()
                .offset(x = animatedOffset)
                .scale(animatedScale)
                .shadow(
                    elevation = if (motionPolicy.spatialTransitionsEnabled && isOpened) {
                        16.dp
                    } else {
                        0.dp
                    },
                    shape = contentShape,
                )
                .clip(contentShape)
                .zIndex(1f)
                .then(
                    if (gestureEnabled) {
                        Modifier.pointerInput(drawerState, gestureEnabled) {
                            var accumulatedDrag = 0f
                            var startX = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { offset ->
                                    accumulatedDrag = 0f
                                    startX = offset.x
                                },
                                onHorizontalDrag = { change, dragAmount ->
                                    change.consume()
                                    accumulatedDrag += dragAmount
                                    if (!isOpened) {
                                        if (startX < EdgeSwipeThresholdPx && accumulatedDrag > SwipeDragThresholdPx) {
                                            onDrawerStateChange(CustomDrawerState.Opened)
                                        }
                                    } else {
                                        if (accumulatedDrag < -SwipeDragThresholdPx) {
                                            onDrawerStateChange(CustomDrawerState.Closed)
                                        }
                                    }
                                },
                            )
                        }
                    } else {
                        Modifier
                    }
                )
                .then(
                    if (isOpened) {
                        Modifier.clickable { onDrawerStateChange(CustomDrawerState.Closed) }
                    } else {
                        Modifier
                    }
                )
        ) {
            content()
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        MaterialTheme.colorScheme.scrim.copy(
                            alpha = scrimAlpha.coerceIn(0f, 1f),
                        ),
                    ),
            )
        }
    }
}
