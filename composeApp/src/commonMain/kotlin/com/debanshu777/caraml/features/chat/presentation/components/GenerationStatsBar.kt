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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import com.debanshu777.caraml.core.theme.AppTheme
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
            .padding(vertical = AppTheme.spacing.spacing8),
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing8),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Live output",
            style = AppTheme.typography.labelSmall,
            color = AppTheme.colors.onSurfaceVariant,
        )
        Text(
            text = "${stats.outputTokenCount}/∞",
            style = AppTheme.typography.numeric12,
            color = AppTheme.colors.onSurface,
        )
        Spacer(modifier = Modifier.weight(1f))

        val formattedSpeed = ((stats.tokensPerSecond * 10).toInt() / 10.0)
        Text(
            text = "$formattedSpeed tok/s",
            modifier = Modifier.semantics {
                contentDescription = "Generation speed $formattedSpeed tokens per second"
            },
            style = AppTheme.typography.technical12,
            color = AppTheme.colors.onSurfaceVariant,
        )
    }
}
