package com.debanshu777.caraml.features.chat.presentation.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.debanshu777.caraml.core.theme.AuroraSurfaceLevel
import com.debanshu777.caraml.core.theme.LocalSpacing
import com.debanshu777.caraml.core.ui.components.CaraMLPane
import com.debanshu777.caraml.core.ui.motion.LocalAuroraMotionPolicy

@Composable
fun GenerationActivity(
    label: String,
    modifier: Modifier = Modifier,
    progress: Float? = null,
) {
    val motion = LocalAuroraMotionPolicy.current
    val activityAlpha = if (motion.pulseEnabled) {
        rememberGenerationPulse(motion.generationPulseMillis)
    } else {
        1f
    }

    CaraMLPane(
        modifier = modifier
            .alpha(activityAlpha)
            .semantics(mergeDescendants = true) { stateDescription = "Generating" },
        level = AuroraSurfaceLevel.Pane,
    ) {
        Column(
            modifier = Modifier.padding(LocalSpacing.current.m),
            verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.s),
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
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
}

@Composable
private fun rememberGenerationPulse(durationMillis: Int): Float {
    val transition = rememberInfiniteTransition(label = "generation activity")
    val alpha by transition.animateFloat(
        initialValue = 0.84f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = durationMillis),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "generation activity alpha",
    )
    return alpha
}
