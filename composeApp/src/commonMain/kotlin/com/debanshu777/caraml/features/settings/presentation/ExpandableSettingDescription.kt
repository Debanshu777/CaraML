package com.debanshu777.caraml.features.settings.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.ui.motion.LocalAuroraMotionPolicy

@Composable
fun ExpandableSettingDescription(
    summary: String,
    details: String,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val motion = LocalAuroraMotionPolicy.current
    val fadeDurationMillis = if (motion.spatialTransitionsEnabled) {
        motion.opacityDurationMillis
    } else {
        motion.opacityDurationMillis.coerceAtMost(90)
    }

    Column(modifier = modifier) {
        Text(
            text = summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier
                .heightIn(min = 48.dp)
                .semantics {
                    stateDescription = if (expanded) "Expanded" else "Collapsed"
                },
        ) {
            Text(if (expanded) "Hide details" else "Show details")
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(fadeDurationMillis)),
            exit = fadeOut(tween(fadeDurationMillis)),
        ) {
            DetailText(details)
        }
    }
}

@Composable
private fun DetailText(details: String) {
    Text(
        text = details,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
