package com.debanshu777.caraml.features.chat.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import com.debanshu777.caraml.core.theme.AppNumericLabel
import com.debanshu777.caraml.core.theme.LocalSpacing
import com.debanshu777.caraml.core.ui.components.CaraMLStatusPill
import com.debanshu777.caraml.core.ui.components.StatusTone
import com.debanshu777.caraml.features.chat.data.LiveGenerationStats
import com.debanshu777.caraml.features.chat.presentation.components.providers.LiveGenerationStatsPreviewProvider

@Preview
@Composable
private fun GenerationStatsBarPreview(
    @PreviewParameter(LiveGenerationStatsPreviewProvider::class) stats: LiveGenerationStats
) {
    MaterialTheme {
        Surface {
            GenerationStatsBar(stats = stats, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun GenerationStatsBar(
    stats: LiveGenerationStats,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = LocalSpacing.current.l, vertical = LocalSpacing.current.s),
        horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.s),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Output",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "${stats.outputTokenCount}/∞",
            style = AppNumericLabel,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.weight(1f))

        val formattedSpeed = ((stats.tokensPerSecond * 10).toInt() / 10.0)
        CaraMLStatusPill(
            label = "$formattedSpeed tok/s",
            contentDescription = "Generation speed $formattedSpeed tokens per second",
            tone = StatusTone.Neutral,
        )
    }
}
