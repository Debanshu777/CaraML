package com.debanshu777.caraml.features.modelhub.presentation.details.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.download.DownloadArtifactState
import com.debanshu777.caraml.core.theme.AppTechnicalLabel
import com.debanshu777.caraml.core.theme.LocalSpacing
import com.debanshu777.caraml.core.theme.auroraColors
import com.debanshu777.caraml.core.theme.prismShapes
import com.debanshu777.caraml.core.ui.motion.LocalAuroraMotionPolicy

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
    GgufFileTechnicalRow(
        filename = filename,
        sizeBytes = sizeBytes,
        isDownloaded = isDownloaded,
        progress = progress,
        isDownloading = isDownloading,
        onDownloadClick = onDownloadClick,
        modifier = modifier,
        downloadEnabled = downloadEnabled,
        interactionLocked = interactionLocked,
        durableState = durableState,
        onPause = onPause,
        onResume = onResume,
        onCancel = onCancel,
        onRetry = onRetry,
        showDownloadAction = true,
    )
}

@Composable
internal fun GgufFileTechnicalRow(
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
    showDownloadAction: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
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
        modifier = modifier
            .fillMaxWidth()
            .then(downloadStateSemantics),
        shape = MaterialTheme.prismShapes.pane,
        color = containerColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        BoxWithConstraints {
            val compact = maxWidth < 480.dp
            val spacing = LocalSpacing.current
            if (compact) {
                Column(
                    modifier = Modifier.padding(spacing.l),
                    verticalArrangement = Arrangement.spacedBy(spacing.s),
                ) {
                    ArtifactIdentity(
                        directory = directory,
                        displayName = displayName,
                        maxNameLines = 2,
                    )
                    if (reportedProgress != null) {
                        ArtifactProgress(reportedProgress, displayedProgress)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        sizeBytes?.let {
                            Text(
                                formatFileSize(it),
                                style = AppTechnicalLabel,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        GgufFileAction(
                            filename = filename,
                            isDownloaded = isDownloaded,
                            isDownloading = isDownloading,
                            downloadEnabled = downloadEnabled,
                            interactionLocked = interactionLocked,
                            durableState = durableState,
                            onDownloadClick = onDownloadClick,
                            onPause = onPause,
                            onResume = onResume,
                            onCancel = onCancel,
                            onRetry = onRetry,
                            showAction = showDownloadAction,
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.l, vertical = spacing.m),
                    horizontalArrangement = Arrangement.spacedBy(spacing.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(spacing.xs),
                    ) {
                        ArtifactIdentity(directory = directory, displayName = displayName)
                        if (reportedProgress != null) {
                            ArtifactProgress(reportedProgress, displayedProgress)
                        }
                    }
                    sizeBytes?.let {
                        Text(
                            formatFileSize(it),
                            style = AppTechnicalLabel,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    GgufFileAction(
                        filename = filename,
                        isDownloaded = isDownloaded,
                        isDownloading = isDownloading,
                        downloadEnabled = downloadEnabled,
                        interactionLocked = interactionLocked,
                        durableState = durableState,
                        onDownloadClick = onDownloadClick,
                        onPause = onPause,
                        onResume = onResume,
                        onCancel = onCancel,
                        onRetry = onRetry,
                        showAction = showDownloadAction,
                    )
                }
            }
        }
    }
}

@Composable
private fun ArtifactIdentity(
    directory: String?,
    displayName: String,
    maxNameLines: Int = 2,
) {
    Column(verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.xs)) {
        if (directory != null) {
            Text(
                text = directory,
                style = AppTechnicalLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Text(
            text = displayName,
            style = AppTechnicalLabel,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = maxNameLines,
        )
    }
}

@Composable
private fun ArtifactProgress(reportedProgress: Float, displayedProgress: Float) {
    LinearProgressIndicator(
        progress = { displayedProgress },
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        "${(reportedProgress * 100f).toInt()}%",
        style = AppTechnicalLabel,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun GgufFileAction(
    filename: String,
    isDownloaded: Boolean,
    isDownloading: Boolean,
    downloadEnabled: Boolean,
    interactionLocked: Boolean,
    durableState: DownloadArtifactState?,
    onDownloadClick: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    showAction: Boolean = true,
) {
    when {
        isDownloaded || durableState == DownloadArtifactState.COMPLETED -> Icon(
            Icons.Default.Check,
            contentDescription = "Downloaded",
            tint = MaterialTheme.colorScheme.primary,
        )
        !showAction -> Unit
        durableState != null &&
            durableState != DownloadArtifactState.CANCELLED &&
            durableState != DownloadArtifactState.FAILED_TERMINAL -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (durableState) {
                    DownloadArtifactState.RUNNING,
                    DownloadArtifactState.QUEUED,
                    DownloadArtifactState.WAITING_FOR_NETWORK,
                    -> IconButton(onClick = onPause, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.Default.Pause,
                            contentDescription = "Pause download $filename",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    DownloadArtifactState.PAUSED -> IconButton(
                        onClick = onResume,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = "Resume download $filename",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    DownloadArtifactState.FAILED_RETRYABLE -> IconButton(
                        onClick = onRetry,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Retry download $filename",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    else -> Unit
                }
                if (durableState !in setOf(
                        DownloadArtifactState.VERIFYING,
                    )
                ) {
                    IconButton(onClick = onCancel, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Cancel download $filename",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
        else -> IconButton(
            onClick = onDownloadClick,
            modifier = Modifier.size(48.dp),
            enabled = downloadEnabled && !interactionLocked && !isDownloading,
        ) {
            Icon(
                Icons.Default.Download,
                contentDescription = "Download $filename",
                tint = MaterialTheme.colorScheme.onSurface,
            )
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
