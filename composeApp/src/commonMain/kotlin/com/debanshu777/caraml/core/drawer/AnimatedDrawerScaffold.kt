package com.debanshu777.caraml.core.drawer

import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.debanshu777.caraml.core.ui.motion.LocalAuroraMotionPolicy

data class DrawerAnimationConfig(
    val drawerWidthFraction: Float = 0.80f,
    val animationDurationMs: Int = 240,
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
    onDrawerClosed: () -> Unit = {},
) {
    val density = LocalDensity.current
    val motionPolicy = LocalAuroraMotionPolicy.current
    val isOpened = drawerState.isOpened()

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
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
                                if (!isOpened && startX < EdgeSwipeThresholdPx &&
                                    accumulatedDrag > SwipeDragThresholdPx
                                ) {
                                    onDrawerStateChange(CustomDrawerState.Opened)
                                } else if (isOpened && accumulatedDrag < -SwipeDragThresholdPx) {
                                    onDrawerStateChange(CustomDrawerState.Closed)
                                }
                            },
                        )
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        val maxWidthPx = constraints.maxWidth.toFloat()
        val drawerWidthPx = maxWidthPx * animationConfig.drawerWidthFraction
        val drawerWidthDp = (drawerWidthPx / density.density).dp
        val transition = updateTransition(
            targetState = isOpened,
            label = "DrawerTransition",
        )
        val drawerOffset by transition.animateDp(
            transitionSpec = {
                if (motionPolicy.spatialTransitionsEnabled) {
                    tween(animationConfig.animationDurationMs)
                } else {
                    tween(0)
                }
            },
            label = "DrawerPanelOffset",
        ) { opened ->
            if (!motionPolicy.spatialTransitionsEnabled || opened) 0.dp else -drawerWidthDp
        }
        val scrimAlpha by transition.animateFloat(
            transitionSpec = {
                if (motionPolicy.spatialTransitionsEnabled) {
                    tween(animationConfig.animationDurationMs)
                } else {
                    tween(motionPolicy.opacityDurationMillis)
                }
            },
            label = "DrawerScrim",
        ) { opened ->
            if (opened) 0.20f else 0f
        }
        val drawerAlpha by transition.animateFloat(
            transitionSpec = {
                if (motionPolicy.spatialTransitionsEnabled) {
                    tween(animationConfig.animationDurationMs)
                } else {
                    tween(motionPolicy.opacityDurationMillis)
                }
            },
            label = "DrawerOpacity",
        ) { opened ->
            if (motionPolicy.spatialTransitionsEnabled || opened) 1f else 0f
        }
        val drawerParticipatingInTransition =
            transition.currentState || transition.targetState
        LaunchedEffect(drawerParticipatingInTransition, isOpened) {
            if (!drawerParticipatingInTransition && !isOpened) {
                onDrawerClosed()
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxSize()
                .zIndex(0f),
        ) {
            content()
        }

        if (drawerParticipatingInTransition) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        MaterialTheme.colorScheme.scrim.copy(
                            alpha = scrimAlpha.coerceIn(0f, 1f),
                        ),
                    )
                    .zIndex(1f)
                    .then(
                        if (isOpened) {
                            Modifier.clickable {
                                onDrawerStateChange(CustomDrawerState.Closed)
                            }
                        } else {
                            Modifier
                        },
                    ),
            )
        }

        if (drawerParticipatingInTransition) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .offset(x = drawerOffset)
                    .alpha(drawerAlpha.coerceIn(0f, 1f))
                    .zIndex(2f),
            ) {
                drawerContent()
            }
        }
    }
}
