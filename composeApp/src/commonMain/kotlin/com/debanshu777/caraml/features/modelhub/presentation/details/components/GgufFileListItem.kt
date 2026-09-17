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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.AppTechnicalLabel
import com.debanshu777.caraml.core.theme.LocalSpacing
import com.debanshu777.caraml.core.theme.auroraColors
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
    showDownloadAction: Boolean = true,
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
    val downloadStateSemantics = if (isDownloading) {
        Modifier.semantics(mergeDescendants = true) {
            stateDescription = "Downloading"
        }
    } else {
        Modifier
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(downloadStateSemantics),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                if (directory != null) {
                    Text(
                        text = directory,
                        style = AppTechnicalLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = displayName,
                        style = AppTechnicalLabel,
                        modifier = Modifier.weight(1f)
                    )
                    if (sizeBytes != null) {
                        Text(
                            formatFileSize(sizeBytes),
                            style = AppTechnicalLabel,
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
                        style = AppTechnicalLabel,
                    )
                }
            }
            if (isDownloaded) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Downloaded",
                    tint = MaterialTheme.colorScheme.primary
                )
            } else if (showDownloadAction) {
                IconButton(
                    onClick = onDownloadClick,
                    modifier = Modifier.size(48.dp),
                    enabled = downloadEnabled && !interactionLocked && !isDownloading,
                ) {
                    Icon(Icons.Default.Download, contentDescription = "Download $filename")
                }
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 16.dp),
            color = MaterialTheme.auroraColors.divider,
            thickness = 1.dp,
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
