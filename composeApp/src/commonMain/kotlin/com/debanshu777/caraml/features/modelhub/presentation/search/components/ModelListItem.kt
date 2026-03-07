package com.debanshu777.caraml.features.modelhub.presentation.search.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.debanshu777.huggingfacemanager.model.ListModelsResponse

@Composable
fun ModelListItem(
    model: ListModelsResponse.Model?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (model == null) return
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = model.id ?: "Unknown",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = buildString {
                    model.author?.let { append("by $it") }
                    model.pipelineTag?.let { if (isNotEmpty()) append(" • ") else Unit; append(it) }
                    append(" • ")
                    append("${model.downloads ?: 0} downloads")
                    append(" • ")
                    append("${model.likes ?: 0} likes")
                    model.numParameters?.let { append(" • ${formatParams(it)} params") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
    HorizontalDivider()
}

private fun formatParams(params: Long): String {
    return when {
        params >= 1_000_000_000 -> "${params / 1_000_000_000}B"
        params >= 1_000_000 -> "${params / 1_000_000}M"
        params >= 1_000 -> "${params / 1_000}K"
        else -> params.toString()
    }
}
