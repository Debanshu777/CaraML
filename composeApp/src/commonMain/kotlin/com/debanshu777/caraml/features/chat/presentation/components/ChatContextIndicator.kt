package com.debanshu777.caraml.features.chat.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.prismShapes
import com.debanshu777.caraml.core.ui.motion.LocalAuroraMotionPolicy
import com.debanshu777.caraml.features.chat.data.LiveGenerationStats
import kotlinx.coroutines.delay

@Composable
internal fun ContextProgressIndicator(
    contextUsed: Int,
    contextLimit: Int,
    modifier: Modifier = Modifier
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val motion = LocalAuroraMotionPolicy.current

    LaunchedEffect(expanded) {
        if (expanded) {
            delay(3000)
            expanded = false
        }
    }

    val reportedProgress = if (contextLimit > 0) {
        (contextUsed.toFloat() / contextLimit).coerceIn(0f, 1f)
    } else {
        0f
    }
    val animatedProgress by animateFloatAsState(
        targetValue = reportedProgress,
        animationSpec = tween(
            durationMillis = if (motion.spatialTransitionsEnabled) 180 else 0,
        ),
        label = "context usage progress",
    )
    val displayedProgress = if (motion.spatialTransitionsEnabled) {
        animatedProgress
    } else {
        reportedProgress
    }
    val percent = if (contextLimit > 0) contextUsed * 100 / contextLimit else 0
    val actionLabel = if (expanded) "Collapse context usage" else "Expand context usage"

    Row(
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clip(MaterialTheme.prismShapes.control)
            .clickable(
                onClickLabel = actionLabel,
                role = Role.Button,
                onClick = { expanded = !expanded },
            )
            .semantics {
                contentDescription = "Context usage"
                stateDescription = if (expanded) "Expanded" else "Collapsed"
            }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CircularProgressIndicator(
            progress = { displayedProgress },
            modifier = Modifier.size(24.dp),
            strokeWidth = 2.dp
        )
        AnimatedVisibility(
            visible = expanded,
            enter = if (motion.spatialTransitionsEnabled) {
                expandHorizontally(
                    animationSpec = tween(motion.peerTransitionMillis),
                    expandFrom = Alignment.Start,
                ) + fadeIn(tween(motion.opacityDurationMillis))
            } else {
                fadeIn(tween(motion.opacityDurationMillis))
            },
            exit = if (motion.spatialTransitionsEnabled) {
                shrinkHorizontally(
                    animationSpec = tween(motion.peerTransitionMillis),
                    shrinkTowards = Alignment.Start,
                ) + fadeOut(tween(motion.opacityDurationMillis))
            } else {
                fadeOut(tween(motion.opacityDurationMillis))
            },
        ) {
            Text(
                text = "$contextUsed/$contextLimit ($percent%)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun RowScope.ContextStatsIndicator(
    liveStats: LiveGenerationStats?,
) {
    if (liveStats != null) {
        ContextProgressIndicator(
            contextUsed = liveStats.contextUsed,
            contextLimit = liveStats.contextLimit,
            modifier = Modifier.align(Alignment.CenterVertically)
        )
        Spacer(modifier = Modifier.width(4.dp))
    }
}
