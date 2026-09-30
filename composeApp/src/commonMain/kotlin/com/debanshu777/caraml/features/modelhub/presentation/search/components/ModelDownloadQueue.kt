package com.debanshu777.caraml.features.modelhub.presentation.search.components

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
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.download.DownloadArtifactSnapshot
import com.debanshu777.caraml.core.download.DownloadArtifactState
import com.debanshu777.caraml.core.download.DownloadBatchSnapshot
import com.debanshu777.caraml.core.download.DownloadBatchState
import com.debanshu777.caraml.core.theme.LocalSpacing
import com.debanshu777.caraml.core.theme.prismShapes

@Composable
fun ModelDownloadQueueEntry(
    batches: List<DownloadBatchSnapshot>,
    onClick: () -> Unit,
) {
    if (batches.isEmpty()) return
    val pending = batches.flatMap { it.artifacts }.filter {
        it.state != DownloadArtifactState.COMPLETED && it.state != DownloadArtifactState.CANCELLED
    }
    if (pending.isEmpty()) return
    val count = pending.size
    val active = pending.count { it.state == DownloadArtifactState.RUNNING || it.state == DownloadArtifactState.VERIFYING }
    val needsAttention = pending.count { it.state == DownloadArtifactState.FAILED_RETRYABLE || it.state == DownloadArtifactState.FAILED_TERMINAL }
    val paused = pending.count { it.state == DownloadArtifactState.PAUSED }
    val waiting = pending.count { it.state == DownloadArtifactState.WAITING_FOR_NETWORK }
    val label = when {
        count == 1 && needsAttention > 0 -> "1 download needs attention"
        count == 1 && paused > 0 -> "1 download paused"
        count == 1 && waiting > 0 -> "1 download waiting for network"
        count == 1 && active > 0 -> "1 download active"
        count == 1 -> "1 download queued"
        needsAttention > 0 -> "$count downloads · $needsAttention need attention"
        active > 0 -> "$count downloads · $active active"
        paused > 0 -> "$count downloads · $paused paused"
        waiting > 0 -> "$count downloads · waiting for network"
        else -> "$count downloads queued"
    }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("model-download-queue-entry"),
        shape = MaterialTheme.prismShapes.control,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = LocalSpacing.current.l, vertical = LocalSpacing.current.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.s),
        ) {
            Icon(Icons.Outlined.Download, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.ChevronRight, contentDescription = "Open downloads")
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
        shape = MaterialTheme.prismShapes.modal,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = LocalSpacing.current.l, vertical = LocalSpacing.current.m),
            verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.m),
        ) {
            Text("Downloads", style = MaterialTheme.typography.titleLarge)
            if (batches.isEmpty()) {
                Text("No active downloads", style = MaterialTheme.typography.bodyMedium)
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
        DownloadArtifactState.VERIFYING -> "Verifying"
        DownloadArtifactState.FAILED_RETRYABLE -> "Download failed · retry available"
        DownloadArtifactState.FAILED_TERMINAL -> "Download failed"
        DownloadArtifactState.CANCELLED -> "Cancelled"
        DownloadArtifactState.COMPLETED -> "Completed"
    }
    Column(
        modifier = Modifier.fillMaxWidth().testTag("download-${artifact.artifactId}"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(batch.displayName, style = MaterialTheme.typography.titleSmall)
        Text(
            artifact.request.metadata.artifact.relativePath,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
        )
        Text(status, style = MaterialTheme.typography.bodyMedium)
        if (artifact.state == DownloadArtifactState.RUNNING || artifact.state == DownloadArtifactState.VERIFYING) {
            val expected = artifact.expectedBytes
            if (artifact.state == DownloadArtifactState.RUNNING && expected > 0L) {
                LinearProgressIndicator(
                    progress = { (artifact.bytesReceived.toFloat() / expected).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("${(artifact.bytesReceived.coerceAtLeast(0) * 100 / expected).coerceIn(0, 100)}%", style = MaterialTheme.typography.bodySmall)
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("Progress unavailable", style = MaterialTheme.typography.bodySmall)
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
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (batch.state) {
                DownloadBatchState.RUNNING, DownloadBatchState.QUEUED,
                DownloadBatchState.WAITING_FOR_NETWORK -> TextButton(onClick = onPause, modifier = Modifier.heightIn(min = 48.dp)) { Text("Pause download") }
                DownloadBatchState.PAUSED -> TextButton(onClick = onResume, modifier = Modifier.heightIn(min = 48.dp)) { Text("Resume download") }
                DownloadBatchState.FAILED_RETRYABLE -> TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry download") }
                else -> Unit
            }
            if (batch.state in listOf(
                    DownloadBatchState.RUNNING, DownloadBatchState.QUEUED,
                    DownloadBatchState.WAITING_FOR_NETWORK, DownloadBatchState.PAUSED,
                    DownloadBatchState.FAILED_RETRYABLE,
                )
            ) {
                TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel download") }
            }
        }
}
