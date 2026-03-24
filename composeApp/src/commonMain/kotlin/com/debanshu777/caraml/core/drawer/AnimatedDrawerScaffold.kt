package com.debanshu777.caraml.core.drawer

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

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
    val isOpened = drawerState.isOpened()

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        val maxWidthPx = constraints.maxWidth.toFloat()
        val offsetValuePx = maxWidthPx * animationConfig.contentOffsetFraction
        val offsetValueDp = (offsetValuePx / density.density).dp

        val animatedOffset by animateDpAsState(
            targetValue = if (isOpened) offsetValueDp else 0.dp,
            animationSpec = tween(animationConfig.animationDurationMs),
            label = "DrawerOffset",
        )

        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .fillMaxWidth(animationConfig.contentOffsetFraction)
        ) {
            drawerContent()
        }

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxSize()
                .offset(x = animatedOffset)
                .shadow(
                    elevation = if (isOpened) 16.dp else 0.dp,
                    shape = MaterialTheme.shapes.medium,
                )
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
        }
    }
}
