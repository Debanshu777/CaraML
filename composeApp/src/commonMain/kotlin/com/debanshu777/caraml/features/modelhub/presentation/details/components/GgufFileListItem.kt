package com.debanshu777.caraml.features.modelhub.presentation.details.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.LocalSpacing
import com.debanshu777.caraml.core.ui.motion.LocalAuroraMotionPolicy
import com.debanshu777.caraml.core.download.DownloadArtifactState

@Composable
fun GgufFileListItem(
    filename: String,
    sizeBytes: Long?,
    isDownloaded: Boolean,
    progress: Float?,
    isDownloading: Boolean,
    onDownloadClick: () -> Unit,
    modifier: Modifier = Modifier,
    downloadEnabled: Boolean = true,
    interactionLocked: Boolean = false,
    durableState: DownloadArtifactState? = null,
    onPause: () -> Unit = {},
    onResume: () -> Unit = {},
    onCancel: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    val hasDirectory = filename.contains('/')
    val displayName = filename.substringAfterLast('/')
    val directory = if (hasDirectory) filename.substringBeforeLast('/') + "/" else null
    val reportedProgress = progress?.takeIf { it >= 0f }?.coerceIn(0f, 100f)?.div(100f)
    val motion = LocalAuroraMotionPolicy.current
    val animatedProgress by animateFloatAsState(
        targetValue = reportedProgress ?: 0f,
        animationSpec = tween(durationMillis = if (motion.spatialTransitionsEnabled) 180 else 0),
    )
    val displayedProgress = if (motion.spatialTransitionsEnabled) {
        animatedProgress
    } else {
        reportedProgress ?: 0f
    }
    val stateText = when (durableState) {
        DownloadArtifactState.QUEUED -> "Queued"
        DownloadArtifactState.RUNNING -> reportedProgress?.let { "Downloading ${(it * 100).toInt()} percent" } ?: "Downloading"
        DownloadArtifactState.PAUSED -> "Paused"
        DownloadArtifactState.WAITING_FOR_NETWORK -> "Waiting for network"
        DownloadArtifactState.VERIFYING -> "Verifying download"
        DownloadArtifactState.FAILED_RETRYABLE -> "Download failed; retry available"
        DownloadArtifactState.COMPLETED -> "Downloaded"
        DownloadArtifactState.FAILED_TERMINAL -> "Download failed"
        DownloadArtifactState.CANCELLED -> "Download cancelled"
        null -> if (isDownloading) "Downloading" else null
    }
    val downloadStateSemantics = if (stateText != null) {
        Modifier.semantics(mergeDescendants = true) {
            stateDescription = stateText
        }
    } else {
        Modifier
    }

    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
        modifier = modifier
            .fillMaxWidth()
            .then(downloadStateSemantics),
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
                        modifier = Modifier.weight(1f)
                    )
                    if (sizeBytes != null) {
                        Text(
                            formatFileSize(sizeBytes),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (reportedProgress != null) {
                    LinearProgressIndicator(
                        progress = { displayedProgress },
                        modifier = Modifier.fillMaxWidth().padding(top = LocalSpacing.current.xs)
                    )
                    Text(
                        "${(reportedProgress * 100f).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            if (isDownloaded || durableState == DownloadArtifactState.COMPLETED) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Downloaded",
                    tint = MaterialTheme.colorScheme.primary
                )
            } else if (durableState != null && durableState != DownloadArtifactState.CANCELLED &&
                durableState != DownloadArtifactState.FAILED_TERMINAL
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (durableState) {
                        DownloadArtifactState.RUNNING,
                        DownloadArtifactState.QUEUED,
                        DownloadArtifactState.WAITING_FOR_NETWORK,
                        -> IconButton(onClick = onPause, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.Pause, contentDescription = "Pause download")
                        }
                        DownloadArtifactState.PAUSED -> IconButton(onClick = onResume, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Resume download")
                        }
                        DownloadArtifactState.FAILED_RETRYABLE -> IconButton(onClick = onRetry, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.Refresh, contentDescription = "Retry download")
                        }
                        else -> Unit
                    }
                    if (durableState !in setOf(DownloadArtifactState.VERIFYING, DownloadArtifactState.FAILED_RETRYABLE)) {
                        IconButton(onClick = onCancel, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel download")
                        }
                    }
                }
            } else {
                IconButton(
                    onClick = onDownloadClick,
                    modifier = Modifier.size(48.dp),
                    enabled = downloadEnabled && !interactionLocked && !isDownloading,
                ) {
                    Icon(Icons.Default.Download, contentDescription = "Download $filename")
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
