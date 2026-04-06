package com.debanshu777.caraml.features.chat.presentation.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.debanshu777.caraml.features.chat.presentation.StreamingState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

@Composable
internal fun ContextProgressIndicator(
    contextUsed: Int,
    contextLimit: Int,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(expanded) {
        if (expanded) {
            delay(3000)
            expanded = false
        }
    }

    val progress = if (contextLimit > 0) {
        (contextUsed.toFloat() / contextLimit).coerceIn(0f, 1f)
    } else {
        0f
    }
    val percent = if (contextLimit > 0) contextUsed * 100 / contextLimit else 0

    Row(
        modifier = modifier
            .clickable { expanded = !expanded }
            .animateContentSize(animationSpec = tween(300)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CircularProgressIndicator(
            progress = { progress },
            modifier = Modifier.size(24.dp),
            strokeWidth = 2.dp
        )
        if (expanded) {
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
    streamingStateFlow: StateFlow<StreamingState>,
) {
    val state by streamingStateFlow.collectAsStateWithLifecycle()
    val liveStats = state.liveStats
    if (liveStats != null) {
        ContextProgressIndicator(
            contextUsed = liveStats.contextUsed,
            contextLimit = liveStats.contextLimit,
            modifier = Modifier.align(Alignment.CenterVertically)
        )
        Spacer(modifier = Modifier.width(4.dp))
    }
}
