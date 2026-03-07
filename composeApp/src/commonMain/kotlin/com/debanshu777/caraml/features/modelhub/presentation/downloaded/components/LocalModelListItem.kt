package com.debanshu777.caraml.features.modelhub.presentation.downloaded.components

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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.huggingfacemanager.model.PipelineTag
import kotlin.math.roundToInt

@Composable
fun LocalModelListItem(
    model: LocalModelEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isSupported = PipelineTag.isSupported(model.pipelineTag)
    
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (isSupported) 1f else 0.38f)
            .clickable(enabled = isSupported, onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = model.modelId,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = buildString {
                    append(model.filename)
                    model.author?.let { append(" • by $it") }
                    model.pipelineTag?.let { 
                        append(" • ")
                        append(it) 
                    }
                    model.sizeBytes?.let { 
                        append(" • ")
                        append(formatSize(it))
                    }
                    model.libraryName?.let {
                        append(" • ")
                        append(it)
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            if (!isSupported) {
                Text(
                    text = "Chat not available for this model type",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
    HorizontalDivider()
}

private fun formatSize(bytes: Long): String {
    fun formatDecimal(value: Double): String {
        val intPart = value.toLong()
        val fracPart = ((value - intPart) * 100).roundToInt().coerceIn(0, 99)
        return "$intPart.${fracPart.toString().padStart(2, '0')}"
    }
    return when {
        bytes >= 1_073_741_824 -> "${formatDecimal(bytes / 1_073_741_824.0)} GB"
        bytes >= 1_048_576 -> "${formatDecimal(bytes / 1_048_576.0)} MB"
        bytes >= 1_024 -> "${formatDecimal(bytes / 1_024.0)} KB"
        else -> "$bytes B"
    }
}
