package com.debanshu777.caraml.features.modelhub.presentation.details.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.rating.SuitabilityRating
import com.debanshu777.caraml.core.rating.ui.SuitabilityDot
import com.debanshu777.caraml.core.theme.LocalSpacing

@Composable
fun GgufFileListItem(
    filename: String,
    sizeBytes: Long?,
    isDownloaded: Boolean,
    progress: Float?,
    isDownloading: Boolean,
    onDownloadClick: () -> Unit,
    modifier: Modifier = Modifier,
    rating: SuitabilityRating? = null,
) {
    val hasDirectory = filename.contains('/')
    val displayName = filename.substringAfterLast('/')
    val directory = if (hasDirectory) filename.substringBeforeLast('/') + "/" else null

    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(LocalSpacing.current.m),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                if (directory != null) {
                    Text(
                        text = directory,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    if (sizeBytes != null) {
                        Text(
                            formatFileSize(sizeBytes),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (rating != null) {
                        SuitabilityDot(
                            rating = rating,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
                if (progress != null && progress >= 0) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth().padding(top = LocalSpacing.current.xs)
                    )
                    Text("${progress.toInt()}%", style = MaterialTheme.typography.labelSmall)
                }
            }
            if (isDownloaded) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Downloaded",
                    tint = MaterialTheme.colorScheme.primary
                )
            } else {
                IconButton(onClick = onDownloadClick, enabled = !isDownloading) {
                    Icon(Icons.Default.Download, contentDescription = "Download")
                }
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
    else -> {
        val gb = bytes / (1024.0 * 1024 * 1024)
        "${(gb * 10).toLong() / 10.0} GB"
    }
}
