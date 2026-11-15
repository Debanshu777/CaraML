package com.debanshu777.caraml.features.modelhub.presentation.details.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.core.download.DownloadArtifactState
import com.debanshu777.caraml.core.theme.CaraMLTheme
import com.debanshu777.caraml.core.theme.ThemePreferences
import com.debanshu777.caraml.core.ui.icons.AppIcons

@Composable
fun ModelDetailsDownloadableListItem(
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
    supportingLabel: String? = null,
    downloadActionDescription: String? = null,
    cancellationPending: Boolean = false,
    variantLabel: String? = null,
    emphasizeDownload: Boolean = false,
) {
    val hasDirectory = filename.contains('/')
    val displayName = filename.substringAfterLast('/')
    val directory = if (hasDirectory) filename.substringBeforeLast('/') + "/" else null
    val reportedProgress = progress?.takeIf { it >= 0f }?.coerceIn(0f, 100f)?.div(100f)
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
    val formattedSize = sizeBytes?.let(::formatFileSize)
    val detailsContent: @Composable () -> Unit = {
        if (variantLabel != null) {
            CompactArtifactIdentity(
                label = variantLabel, filename = filename,
                metadata = listOfNotNull(filename.substringAfterLast('.', "").uppercase().takeIf(String::isNotBlank), formattedSize).joinToString(" · "),
            )
        } else {
            ArtifactIdentity(directory = directory, displayName = displayName)
        }
        supportingLabel?.let { label ->
            Text(
                text = label,
                style = AppTheme.typography.labelSmall,
                color = AppTheme.colors.onSurfaceVariant
            )
        }
    }
    val actionContent: @Composable () -> Unit = {
        GgufFileAction(
            filename = filename,
            isDownloaded = isDownloaded,
            isDownloading = isDownloading,
            downloadEnabled = downloadEnabled,
            interactionLocked = interactionLocked,
            durableState = durableState,
            cancellationPending = cancellationPending,
            onDownloadClick = onDownloadClick,
            onPause = onPause,
            onResume = onResume,
            onCancel = onCancel,
            onRetry = onRetry,
            showAction = showDownloadAction,
            actionDescription = downloadActionDescription,
            progress = progress,
            emphasizeDownload = emphasizeDownload,
        )
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .then(downloadStateSemantics),
    ) {
        val compact = maxWidth < 240.dp || LocalDensity.current.fontScale >= 1.5f ||
            isDownloading || cancellationPending || durableState in setOf(
                DownloadArtifactState.QUEUED, DownloadArtifactState.RUNNING,
                DownloadArtifactState.PAUSED, DownloadArtifactState.WAITING_FOR_NETWORK,
                DownloadArtifactState.FAILED_RETRYABLE,
            )
        val spacing = AppTheme.spacing
        if (compact) {
            Column(
                modifier = Modifier.padding(vertical = spacing.spacing8),
                verticalArrangement = Arrangement.spacedBy(spacing.spacing4),
            ) {
                detailsContent()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    formattedSize?.takeIf { variantLabel == null }?.let {
                        Text(
                            it,
                            style = AppTheme.typography.labelSmall,
                            color = AppTheme.colors.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    actionContent()
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = spacing.spacing8),
                horizontalArrangement = Arrangement.spacedBy(spacing.spacing8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(spacing.spacing4),
                ) {
                    detailsContent()
                    formattedSize?.takeIf { variantLabel == null }?.let {
                        Text(it, style = AppTheme.typography.labelSmall, color = AppTheme.colors.onSurfaceVariant)
                    }
                }
                actionContent()
            }
        }
    }
}

@Composable
private fun CompactArtifactIdentity(label: String, filename: String, metadata: String) {
    Column(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "Variant $label. File $filename"
            },
        verticalArrangement = Arrangement.Center,
    ) {
        Text(label, style = AppTheme.typography.heading16, color = AppTheme.colors.onSurface)
        Text(metadata, style = AppTheme.typography.labelBase, color = AppTheme.colors.onSurfaceVariant)
        Text(filename, style = AppTheme.typography.bodySmall, color = AppTheme.colors.onSurface,
            modifier = Modifier.padding(top = AppTheme.spacing.spacing4))
    }
}

@Composable
private fun ArtifactIdentity(
    directory: String?,
    displayName: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing4)) {
        if (directory != null) {
            Text(
                text = directory,
                style = AppTheme.typography.technical12,
                color = AppTheme.colors.onSurfaceVariant,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = displayName,
            style = AppTheme.typography.bodySmallMedium,
            color = AppTheme.colors.onSurface,
            overflow = TextOverflow.Ellipsis
        )
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

@Preview(name = "Downloadable files - compact", widthDp = 360, heightDp = 520)
@Composable
private fun DownloadableFilesCompactPreview() {
    DownloadableFilesPreviewContent()
}

@Preview(name = "Downloadable files - wide", widthDp = 720, heightDp = 360)
@Composable
private fun DownloadableFilesWidePreview() {
    DownloadableFilesPreviewContent()
}

@Preview(name = "Downloadable files - large text", widthDp = 360, heightDp = 900, fontScale = 2f)
@Composable
private fun DownloadableFilesLargeTextPreview() {
    DownloadableFilesPreviewContent()
}

@Composable
private fun DownloadableFilesPreviewContent() {
    CaraMLTheme(ThemePreferences()) {
        Surface {
            Column(
                modifier = Modifier.padding(AppTheme.spacing.spacing12),
            ) {
                ModelDetailsDownloadableListItem(
                    filename = "weights/MiniCPM5-2B-Q4_K_M.gguf",
                    sizeBytes = 1_610_612_736L,
                    isDownloaded = false,
                    progress = null,
                    isDownloading = false,
                    onDownloadClick = {},
                    supportingLabel = "Recommended artifact",
                )
                ModelDetailsDownloadableListItem(
                    filename = "weights/MiniCPM5-2B-Q5_K_M.gguf",
                    sizeBytes = 2_147_483_648L,
                    isDownloaded = false,
                    progress = 42f,
                    isDownloading = true,
                    durableState = DownloadArtifactState.RUNNING,
                    onDownloadClick = {},
                )
                ModelDetailsDownloadableListItem(
                    filename = "weights/MiniCPM5-2B-Q8_0.gguf",
                    sizeBytes = 3_221_225_472L,
                    isDownloaded = true,
                    progress = null,
                    isDownloading = false,
                    durableState = DownloadArtifactState.COMPLETED,
                    onDownloadClick = {},
                )
            }
        }
    }
}
