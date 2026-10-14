package com.debanshu777.caraml.features.modelhub.presentation.details.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.core.ui.components.BrandButton
import com.debanshu777.caraml.core.ui.components.BrandButtonStyle
import com.debanshu777.caraml.core.download.DownloadArtifactState
import com.debanshu777.caraml.core.theme.CaraMLTheme
import com.debanshu777.caraml.core.theme.ThemePreferences

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
        ArtifactIdentity(directory = directory, displayName = displayName)
        supportingLabel?.let { label ->
            Text(
                text = label,
                style = AppTheme.typography.labelSmall,
                color = AppTheme.colors.tertiary
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
            onDownloadClick = onDownloadClick,
            onPause = onPause,
            onResume = onResume,
            onCancel = onCancel,
            onRetry = onRetry,
            showAction = showDownloadAction,
            actionDescription = downloadActionDescription,
            progress = progress,
        )
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .then(downloadStateSemantics),
    ) {
        val compact = maxWidth < AppTheme.dimensions.size480 || LocalDensity.current.fontScale >= 1.5f
        val spacing = AppTheme.spacing
        if (compact) {
            Column(
                modifier = Modifier.padding(vertical = spacing.spacing12),
                verticalArrangement = Arrangement.spacedBy(spacing.spacing4),
            ) {
                detailsContent()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    formattedSize?.let {
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
                    .padding(vertical = spacing.spacing12),
                horizontalArrangement = Arrangement.spacedBy(spacing.spacing8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(spacing.spacing4),
                ) {
                    detailsContent()
                }
                formattedSize?.let {
                    Text(
                        it,
                        style = AppTheme.typography.body14,
                        color = AppTheme.colors.onSurfaceVariant,
                    )
                }
                actionContent()
            }
        }
    }
}

@Composable
private fun ArtifactIdentity(
    directory: String?,
    displayName: String,
) {
    var expanded by rememberSaveable(directory, displayName) { mutableStateOf(false) }
    var directoryOverflows by rememberSaveable(directory, displayName) { mutableStateOf(false) }
    var nameOverflows by rememberSaveable(directory, displayName) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing4)) {
        if (directory != null) {
            Text(
                text = directory,
                style = AppTheme.typography.technical12,
                color = AppTheme.colors.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!expanded) directoryOverflows = it.hasVisualOverflow },
            )
        }
        Text(
            text = displayName,
            style = AppTheme.typography.body17,
            color = AppTheme.colors.onSurface,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) nameOverflows = it.hasVisualOverflow },
        )
        if (expanded || directoryOverflows || nameOverflows) {
            BrandButton(style = BrandButtonStyle.Secondary, onClick = { expanded = !expanded }) {
                Text(if (expanded) "Show less" else "Show full filename")
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
