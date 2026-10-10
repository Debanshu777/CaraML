package com.debanshu777.caraml.features.modelhub.presentation.search.components

import com.debanshu777.caraml.core.ui.icons.AppIcons
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.core.ui.components.BrandButton
import com.debanshu777.caraml.core.ui.components.BrandButtonStyle
import com.debanshu777.caraml.core.ui.motion.LocalAuroraMotionPolicy
import com.debanshu777.caraml.core.download.DownloadArtifactSnapshot
import com.debanshu777.caraml.core.download.DownloadArtifactState
import com.debanshu777.caraml.core.download.DownloadBatchSnapshot
import com.debanshu777.caraml.core.download.DownloadBatchState
import com.debanshu777.caraml.core.download.DownloadUserIntent

@Composable
fun ModelDownloadQueueEntry(
    batches: List<DownloadBatchSnapshot>,
    onClick: () -> Unit,
    onPause: ((String, String) -> Unit)? = null,
    onResume: ((String, String) -> Unit)? = null,
    onRetry: ((String, String) -> Unit)? = null,
    onCancel: ((String, String) -> Unit)? = null,
) {
    val pending = batches.flatMap { batch ->
        batch.artifacts.filter {
            it.state != DownloadArtifactState.COMPLETED && it.state != DownloadArtifactState.CANCELLED
        }.map { batch to it }
    }
    val (batch, artifact) = pending.firstOrNull() ?: return
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = AppTheme.spacing.spacing8)
            .testTag("model-download-queue-entry"),
        shape = AppTheme.shapes.medium,
        border = BorderStroke(1.dp, AppTheme.colors.outlineVariant),
        color = AppTheme.colors.surfaceContainerLowest,
    ) {
        Column(
            modifier = Modifier.padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing8),
        ) {
            ModelDownloadQueueRow(batch = batch, artifact = artifact)
            if (onPause != null && onResume != null && onRetry != null && onCancel != null) {
                ModelDownloadBatchControls(
                    batch = batch,
                    onPause = { onPause(batch.batchId, artifact.artifactId) },
                    onResume = { onResume(batch.batchId, artifact.artifactId) },
                    onRetry = { onRetry(batch.batchId, artifact.artifactId) },
                    onCancel = { onCancel(batch.batchId, artifact.artifactId) },
                )
            }
            BrandButton(style = BrandButtonStyle.Secondary, onClick = onClick, modifier = Modifier.heightIn(min = AppTheme.spacing.spacing48)) {
                Text(if (pending.size > 1) "All downloads (${pending.size})" else "Download details")
                Icon(AppIcons.ChevronRight, contentDescription = "Open downloads")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelDownloadQueueSheet(
    batches: List<DownloadBatchSnapshot>,
    onDismiss: () -> Unit,
    onPause: (String, String) -> Unit,
    onResume: (String, String) -> Unit,
    onRetry: (String, String) -> Unit,
    onCancel: (String, String) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = AppTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = AppTheme.spacing.spacing16, vertical = AppTheme.spacing.spacing12),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing12),
        ) {
            Text("Downloads", style = AppTheme.typography.headingBase)
            if (batches.isEmpty()) {
                Text("No active downloads", style = AppTheme.typography.bodyBase)
            }
            batches.forEach { batch ->
                batch.artifacts.filter {
                    it.state != DownloadArtifactState.COMPLETED && it.state != DownloadArtifactState.CANCELLED
                }.forEach { artifact ->
                    ModelDownloadQueueRow(
                        batch = batch,
                        artifact = artifact,
                    )
                }
                val controlArtifact = batch.artifacts.firstOrNull {
                    it.state != DownloadArtifactState.COMPLETED && it.state != DownloadArtifactState.CANCELLED
                }
                if (controlArtifact != null) {
                    ModelDownloadBatchControls(
                        batch = batch,
                        onPause = { onPause(batch.batchId, controlArtifact.artifactId) },
                        onResume = { onResume(batch.batchId, controlArtifact.artifactId) },
                        onRetry = { onRetry(batch.batchId, controlArtifact.artifactId) },
                        onCancel = { onCancel(batch.batchId, controlArtifact.artifactId) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun ModelDownloadQueueRow(
    batch: DownloadBatchSnapshot,
    artifact: DownloadArtifactSnapshot,
) {
    val status = when (artifact.state) {
        DownloadArtifactState.QUEUED -> "Queued"
        DownloadArtifactState.RUNNING -> "Downloading"
        DownloadArtifactState.PAUSED -> "Paused"
        DownloadArtifactState.WAITING_FOR_NETWORK -> "Waiting for network"
        DownloadArtifactState.VERIFYING -> if (batch.userIntent == DownloadUserIntent.PAUSE) "Verification paused" else "Verifying"
        DownloadArtifactState.FAILED_RETRYABLE -> "Download failed · retry available"
        DownloadArtifactState.FAILED_TERMINAL -> "Download failed"
        DownloadArtifactState.CANCELLED -> "Cancelled"
        DownloadArtifactState.COMPLETED -> "Completed"
    }
    Column(
        modifier = Modifier.fillMaxWidth().testTag("download-${artifact.artifactId}"),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing4),
    ) {
        Text(batch.displayName, style = AppTheme.typography.bodyBase)
        Text(
            artifact.request.metadata.artifact.relativePath,
            style = AppTheme.typography.bodySmall,
            color = AppTheme.colors.onSurfaceVariant,
            maxLines = 2,
        )
        Text(status, style = AppTheme.typography.labelSmall, color = AppTheme.colors.onSurfaceVariant)
        if (artifact.state != DownloadArtifactState.CANCELLED) {
            val expected = artifact.expectedBytes
            if (expected > 0L) {
                LinearProgressIndicator(
                    progress = { (artifact.bytesReceived.toFloat() / expected).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("${formatStorageBytes(artifact.bytesReceived)} of ${formatStorageBytes(expected)} · ${(artifact.bytesReceived.toDouble() * 100 / expected).toInt().coerceIn(0, 100)}%", style = AppTheme.typography.labelSmall, color = AppTheme.colors.onSurfaceVariant)
            } else {
                if (LocalAuroraMotionPolicy.current.pulseEnabled && artifact.state in listOf(DownloadArtifactState.RUNNING, DownloadArtifactState.VERIFYING)) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text("Progress unavailable", style = AppTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModelDownloadBatchControls(
    batch: DownloadBatchSnapshot,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing8)) {
            when (batch.state) {
                DownloadBatchState.RUNNING, DownloadBatchState.QUEUED,
                DownloadBatchState.WAITING_FOR_NETWORK -> ModelHubAction(label = "Pause download", onClick = onPause)
                DownloadBatchState.PAUSED -> ModelHubAction(label = "Resume download", onClick = onResume)
                DownloadBatchState.VERIFYING -> if (batch.userIntent == DownloadUserIntent.PAUSE) {
                    ModelHubAction(label = "Resume download", onClick = onResume)
                }
                DownloadBatchState.FAILED_RETRYABLE -> ModelHubAction(label = "Retry download", onClick = onRetry)
                else -> Unit
            }
            if (batch.state in listOf(
                    DownloadBatchState.RUNNING, DownloadBatchState.QUEUED,
                    DownloadBatchState.WAITING_FOR_NETWORK, DownloadBatchState.PAUSED,
                    DownloadBatchState.FAILED_RETRYABLE,
                )
            ) {
                ModelHubAction(label = "Cancel download", onClick = onCancel)
            }
        }
}
