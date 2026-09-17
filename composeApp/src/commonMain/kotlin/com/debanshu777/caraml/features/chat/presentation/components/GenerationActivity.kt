package com.debanshu777.caraml.features.chat.presentation.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.LocalSpacing
import com.debanshu777.caraml.core.ui.motion.LocalAuroraMotionPolicy

enum class GenerationActivityPhase(
    val description: String,
    val icon: ImageVector,
) {
    Preparing("Preparing", Icons.Default.AccessTime),
    Generating("Generating", Icons.Default.AutoAwesome),
    Finalizing("Finalizing", Icons.Default.DataUsage),
}

/**
 * Transparent generation state trace. The caller supplies only a phase it can prove from current
 * runtime state; this component does not manufacture lifecycle stages or inferred progress.
 */
@Composable
fun GenerationActivity(
    label: String,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    phase: GenerationActivityPhase = if (progress == null) {
        GenerationActivityPhase.Preparing
    } else {
        GenerationActivityPhase.Generating
    },
) {
    val motion = LocalAuroraMotionPolicy.current
    val signalAlpha = if (progress != null && motion.pulseEnabled) {
        rememberGenerationPulse(motion.generationPulseMillis)
    } else {
        1f
    }

    Column(
        modifier = modifier.semantics {
            stateDescription = phase.description
        },
        verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.s),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = phase.icon,
                contentDescription = null,
                modifier = Modifier
                    .size(20.dp)
                    .alpha(signalAlpha)
                    .testTag("generation-activity-signal"),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.xxs),
            ) {
                Text(
                    text = phase.description,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun rememberGenerationPulse(durationMillis: Int): Float {
    val transition = rememberInfiniteTransition(label = "generation activity signal")
    val alpha by transition.animateFloat(
        initialValue = 0.62f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = durationMillis),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "generation activity signal alpha",
    )
    return alpha
}
