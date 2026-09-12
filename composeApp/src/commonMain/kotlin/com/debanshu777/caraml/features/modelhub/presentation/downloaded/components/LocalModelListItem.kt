package com.debanshu777.caraml.features.modelhub.presentation.downloaded.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.debanshu777.caraml.core.theme.AuroraSurfaceLevel
import com.debanshu777.caraml.core.ui.components.CaraMLPane
import com.debanshu777.caraml.core.ui.components.CaraMLStatusPill
import com.debanshu777.caraml.core.ui.components.StatusTone
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

    val cardShape = if (isSelected) MaterialTheme.shapes.large else MaterialTheme.shapes.medium
    CaraMLPane(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .fillMaxWidth()
            .alpha(visibleAlpha)
            .let {
                if (isSelected) {
                    it.border(1.5.dp, MaterialTheme.colorScheme.primary, cardShape)
                } else {
                    it
                }
            }
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
        level = AuroraSurfaceLevel.Pane,
        shape = cardShape,
        showBorder = !isSelected,
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
                Text(
                    text = model.modelId,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
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
                Column(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (isSelected) {
                        CaraMLStatusPill(
                            label = "Selected",
                            contentDescription = "Selected model ${model.modelId}.",
                            tone = StatusTone.Accent,
                            icon = Icons.Filled.CheckBox,
                        )
                    }
                    when {
                        isPartial -> CaraMLStatusPill(
                            label = "Needs setup",
                            contentDescription = "Partial download. Missing components need setup.",
                            tone = StatusTone.Warning,
                            icon = Icons.Default.Build,
                        )
                        isReady -> CaraMLStatusPill(
                            label = "Ready",
                            contentDescription = "Ready for chat.",
                            tone = StatusTone.Success,
                            icon = Icons.Default.CheckCircle,
                        )
                    }
                    if (!isSupported) {
                        CaraMLStatusPill(
                            label = "Unsupported",
                            contentDescription = "Unsupported. Chat is not available for this model type.",
                            tone = StatusTone.Error,
                            icon = Icons.Default.Block,
                        )
                    }
                }
                if (isPartial && onFixComponents != null && !selectionMode) {
                    TextButton(
                        onClick = onFixComponents,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .heightIn(min = 48.dp),
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
