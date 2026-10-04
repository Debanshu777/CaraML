package com.debanshu777.caraml.features.modelhub.presentation.details.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.core.download.DownloadArtifactState
import com.debanshu777.caraml.core.theme.CaraMLTheme
import com.debanshu777.caraml.core.theme.ThemePreferences
import com.debanshu777.caraml.core.ui.motion.LocalAuroraMotionPolicy

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
    actionDescription: String? = null,
    progress: Float? = null,
) {
    val reportedProgress = progress?.takeIf { it >= 0f }?.coerceIn(0f, 100f)?.div(100f)
    when {
        isDownloaded || durableState == DownloadArtifactState.COMPLETED -> Icon(
            Icons.Default.Check,
            contentDescription = "Downloaded",
            tint = AppTheme.colors.onSurface,
        )
        !showAction -> Unit
        durableState != null &&
            durableState != DownloadArtifactState.CANCELLED &&
            durableState != DownloadArtifactState.FAILED_TERMINAL -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                reportedProgress?.let { ArtifactActionProgress(it) }
                when (durableState) {
                    DownloadArtifactState.RUNNING,
                    DownloadArtifactState.QUEUED,
                    DownloadArtifactState.WAITING_FOR_NETWORK,
                    -> IconButton(onClick = onPause, modifier = Modifier.size(AppTheme.dimensions.size30)) {
                        Icon(
                            Icons.Default.Pause,
                            contentDescription = "Pause download $filename",
                            tint = AppTheme.colors.onSurface,
                        )
                    }
                    DownloadArtifactState.PAUSED -> IconButton(
                        onClick = onResume,
                        modifier = Modifier.size(AppTheme.dimensions.size30),
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = "Resume download $filename",
                            tint = AppTheme.colors.onSurface,
                        )
                    }
                    DownloadArtifactState.FAILED_RETRYABLE -> IconButton(
                        onClick = onRetry,
                        modifier = Modifier.size(AppTheme.dimensions.size30),
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Retry download $filename",
                            tint = AppTheme.colors.onSurface,
                        )
                    }
                    else -> Unit
                }
                if (durableState !in setOf(
                        DownloadArtifactState.VERIFYING,
                    )
                ) {
                    IconButton(onClick = onCancel, modifier = Modifier.size(AppTheme.dimensions.size30)) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Cancel download $filename",
                            tint = AppTheme.colors.onSurface,
                        )
                    }
                }
            }
        }
        else -> Row(verticalAlignment = Alignment.CenterVertically) {
            reportedProgress?.let { ArtifactActionProgress(it) }
            IconButton(
                onClick = onDownloadClick,
                modifier = Modifier.size(AppTheme.dimensions.size30),
                enabled = downloadEnabled && !interactionLocked && !isDownloading,
            ) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = actionDescription ?: "Download $filename",
                    tint = AppTheme.colors.onSurface,
                )
            }
        }
    }
}

@Composable
private fun ArtifactActionProgress(reportedProgress: Float) {
    val motion = LocalAuroraMotionPolicy.current
    val animatedProgress by animateFloatAsState(
        targetValue = reportedProgress,
        animationSpec = tween(durationMillis = if (motion.spatialTransitionsEnabled) 180 else 0),
    )
    val displayedProgress = if (motion.spatialTransitionsEnabled) animatedProgress else reportedProgress
    Row(verticalAlignment = Alignment.CenterVertically) {
        LinearProgressIndicator(
            modifier = Modifier.width(AppTheme.dimensions.size120),
            progress = { displayedProgress }
        )
        Spacer(Modifier.width(AppTheme.spacing.spacing8))
        Text(
            "${(reportedProgress * 100f).toInt()}%",
            style = AppTheme.typography.technical12,
            color = AppTheme.colors.onSurfaceVariant,
        )
        Spacer(Modifier.width(AppTheme.spacing.spacing8))
    }
}

@Preview(name = "Artifact download actions", widthDp = 400, heightDp = 520)
@Composable
private fun ArtifactDownloadActionsPreview() {
    ArtifactDownloadActionsPreviewContent()
}

@Preview(name = "Artifact download actions - large text", widthDp = 520, heightDp = 800, fontScale = 2f)
@Composable
private fun ArtifactDownloadActionsLargeTextPreview() {
    ArtifactDownloadActionsPreviewContent()
}

@Composable
private fun ArtifactDownloadActionsPreviewContent() {
    CaraMLTheme(ThemePreferences()) {
        Surface {
            Column(
                modifier = Modifier.padding(AppTheme.spacing.spacing16),
                verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing8),
            ) {
                ArtifactDownloadActionPreviewRow("Available")
                ArtifactDownloadActionPreviewRow("Disabled", downloadEnabled = false)
                ArtifactDownloadActionPreviewRow("Downloading", state = DownloadArtifactState.RUNNING, progress = 42f)
                ArtifactDownloadActionPreviewRow("Paused", state = DownloadArtifactState.PAUSED, progress = 42f)
                ArtifactDownloadActionPreviewRow("Retry", state = DownloadArtifactState.FAILED_RETRYABLE, progress = 42f)
                ArtifactDownloadActionPreviewRow("Verifying", state = DownloadArtifactState.VERIFYING, progress = 100f)
                ArtifactDownloadActionPreviewRow("Downloaded", state = DownloadArtifactState.COMPLETED)
            }
        }
    }
}

@Composable
private fun ArtifactDownloadActionPreviewRow(
    label: String,
    state: DownloadArtifactState? = null,
    downloadEnabled: Boolean = true,
    progress: Float? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = AppTheme.typography.bodyBase)
        Spacer(Modifier.weight(1f))
        GgufFileAction(
            filename = "model.gguf",
            isDownloaded = state == DownloadArtifactState.COMPLETED,
            isDownloading = state == DownloadArtifactState.RUNNING,
            downloadEnabled = downloadEnabled,
            interactionLocked = false,
            durableState = state,
            onDownloadClick = {},
            onPause = {},
            onResume = {},
            onCancel = {},
            onRetry = {},
            progress = progress,
        )
    }
}
