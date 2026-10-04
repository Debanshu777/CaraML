package com.debanshu777.caraml.features.chat.presentation.components

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.AppTheme
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
    val reportedProgress = progress?.coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = reportedProgress ?: 0f,
        animationSpec = tween(
            durationMillis = if (motion.spatialTransitionsEnabled) 180 else 0,
        ),
        label = "generation activity progress",
    )
    val displayedProgress = if (motion.spatialTransitionsEnabled) {
        animatedProgress
    } else {
        reportedProgress ?: 0f
    }

    Column(
        modifier = modifier.semantics {
            stateDescription = phase.description
        },
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing8),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = phase.icon,
                contentDescription = null,
                modifier = Modifier
                    .size(AppTheme.dimensions.size20)
                    .testTag("generation-activity-signal"),
                tint = AppTheme.colors.primary,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing2),
            ) {
                Text(
                    text = phase.description,
                    style = AppTheme.typography.labelBase,
                    color = AppTheme.colors.onSurface,
                )
                Text(
                    text = label,
                    style = AppTheme.typography.bodySmall,
                    color = AppTheme.colors.onSurfaceVariant,
                )
            }
        }
        if (progress != null) {
            LinearProgressIndicator(
                progress = { displayedProgress },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}
