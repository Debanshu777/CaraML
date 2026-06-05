package com.debanshu777.caraml.features.modelhub.presentation.downloaded.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.core.storage.localmodel.displayFilename
import com.debanshu777.huggingfacemanager.model.DIFFUSERS_BUNDLE_DB_FILENAME
import com.debanshu777.huggingfacemanager.model.PipelineTag
import kotlin.math.roundToInt

@Composable
fun LocalModelListItem(
    model: LocalModelEntity,
    selectionMode: Boolean,
    isSelected: Boolean,
    onOpenModel: () -> Unit,
    onToggleSelect: () -> Unit,
    onLongPress: () -> Unit,
    onFixComponents: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val isSupported = PipelineTag.isSupported(model.pipelineTag) ||
        model.filename == DIFFUSERS_BUNDLE_DB_FILENAME ||
        model.filename.endsWith(".gguf", ignoreCase = true) ||
        model.filename.endsWith(".safetensors", ignoreCase = true) ||
        model.filename.endsWith(".ckpt", ignoreCase = true) ||
        model.filename.endsWith(".pth", ignoreCase = true)

    val isPartial = model.componentStatus == LocalModelEntity.STATUS_PARTIAL
    val isReady = model.componentStatus == LocalModelEntity.STATUS_READY
    val visibleAlpha = if (selectionMode || isSupported) 1f else 0.38f

    val openDescription = "Open model ${model.modelId}"
    val selectDescription =
        if (isSelected) "Selected ${model.modelId}, tap to deselect" else "Not selected ${model.modelId}, tap to select"

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .alpha(visibleAlpha)
            .semantics {
                contentDescription = if (selectionMode) selectDescription else openDescription
            }
            .combinedClickable(
                onClick = {
                    when {
                        selectionMode -> onToggleSelect()
                        isSupported -> onOpenModel()
                    }
                },
                onLongClick = onLongPress
            ),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.surface
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode) {
                Icon(
                    imageVector = if (isSelected) Icons.Filled.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                // Model ID + optional "Ready" chip
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = model.modelId,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    when {
                        isReady -> ReadinessPill(label = "Ready", isReady = true)
                        isPartial -> ReadinessPill(label = "Partial", isReady = false)
                        // null componentStatus → no pill (self-contained or not yet reconciled)
                    }
                }
                Text(
                    text = buildString {
                        append(model.displayFilename())
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
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (!isSupported) {
                    Text(
                        text = "Chat not available for this model type",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                // "Fix" action for partial models
                if (isPartial && onFixComponents != null && !selectionMode) {
                    TextButton(
                        onClick = onFixComponents,
                        modifier = Modifier.padding(top = 2.dp),
                    ) {
                        Icon(
                            Icons.Default.Build,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Download missing components",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
    HorizontalDivider()
}

@Composable
private fun ReadinessPill(label: String, isReady: Boolean) {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = if (isReady) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.errorContainer,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (isReady) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
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
