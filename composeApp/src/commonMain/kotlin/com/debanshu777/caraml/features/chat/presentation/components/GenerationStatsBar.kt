package com.debanshu777.caraml.features.chat.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.features.chat.data.LiveGenerationStats
import com.debanshu777.caraml.features.chat.presentation.components.preview.providers.LiveGenerationStatsPreviewProvider

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
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatChip(
            modifier = Modifier.weight(1f),
            label = "Output: ${stats.outputTokenCount}/∞"
        )
        
        val formattedSpeed = ((stats.tokensPerSecond * 10).toInt() / 10.0)
        StatChip(
            modifier = Modifier.weight(1f),
            label = "$formattedSpeed tokens/sec"
        )
    }
}

@Composable
private fun StatChip(
    label: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Text(
            text = label,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}
