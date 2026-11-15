package com.debanshu777.caraml.features.modelhub.presentation.search.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
import kotlin.math.abs

@Composable
internal fun Modifier.pullToSearch(listState: LazyListState, onPull: (() -> Unit)?): Modifier {
    val currentOnPull by rememberUpdatedState(onPull)
    if (onPull == null) return this
    return pointerInput(listState) {
        val pullDistance = 56.dp.toPx()
        val flingDistance = 32.dp.toPx()
        val flingVelocity = 550.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (listState.canScrollBackward || listState.isScrollInProgress) return@awaitEachGesture
            val tracker = VelocityTracker().also { it.addPosition(down.uptimeMillis, down.position) }
            var claimed = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val movement = event.changes.singleOrNull()?.takeIf { it.id == down.id } ?: break
                if (movement.isConsumed) break
                val delta = movement.position - down.position
                if (!claimed && (delta.y < -viewConfiguration.touchSlop ||
                        abs(delta.x) > viewConfiguration.touchSlop && abs(delta.x) > delta.y)
                ) break
                if (!claimed && movement.pressed &&
                    delta.y > viewConfiguration.touchSlop && delta.y > abs(delta.x) * 1.4f
                ) claimed = true
                if (movement.pressed) tracker.addPosition(movement.uptimeMillis, movement.position)
                if (claimed) movement.consume()
                if (!movement.pressed) {
                    val releasedDuringFling = movement.uptimeMillis - down.uptimeMillis < 280
                    if (claimed && (delta.y >= pullDistance ||
                            delta.y >= flingDistance && releasedDuringFling &&
                            tracker.calculateVelocity().y >= flingVelocity)
                    ) currentOnPull?.invoke()
                    break
                }
            }
        }
    }
}
