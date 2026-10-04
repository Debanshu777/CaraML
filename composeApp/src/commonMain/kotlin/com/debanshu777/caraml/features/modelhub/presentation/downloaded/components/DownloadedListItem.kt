package com.debanshu777.caraml.features.modelhub.presentation.downloaded.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.core.storage.localmodel.displayFilename
import com.debanshu777.caraml.core.theme.CaraMLTheme
import com.debanshu777.caraml.core.theme.ThemePreferences
import com.debanshu777.caraml.core.ui.components.StatusMark
import com.debanshu777.caraml.core.ui.components.GenericListItem
import com.debanshu777.huggingfacemanager.model.DIFFUSERS_BUNDLE_DB_FILENAME
import com.debanshu777.huggingfacemanager.model.PipelineTag
import kotlin.math.roundToInt

@Composable
fun DownloadedListItem(
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
    val openDescription = "Open model ${model.modelId}"
    val selectDescription = if (isSelected) {
        "Selected ${model.modelId}, tap to deselect"
    } else {
        "Not selected ${model.modelId}, tap to select"
    }
    val rowDescription = if (selectionMode) selectDescription else openDescription
    val metadata = buildList {
        model.sizeBytes?.let { add(formatSize(it)) }
        model.pipelineTag?.takeUnless { it == "text-generation" }?.let(::add)
    }.joinToString(" · ").ifBlank { null }
    val statusContent: (@Composable () -> Unit)? = if (isPartial || isReady || !isSupported) {
        {
            Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing4)) {
                if (isPartial) {
                    LocalModelStatusMark(
                        label = "Needs setup",
                        description = "Partial download. Missing components need setup.",
                        icon = Icons.Default.Build,
                    )
                }
                if (isReady) {
                    LocalModelStatusMark(
                        label = "Ready",
                        description = "Ready for chat.",
                        icon = Icons.Default.CheckCircle,
                    )
                }
                if (!isSupported) {
                    LocalModelStatusMark(
                        label = "Unsupported",
                        description = "Unsupported. Chat is not available for this model type.",
                        icon = Icons.Default.Block,
                    )
                }
            }
        }
    } else {
        null
    }
    val fixComponentsAction: (@Composable () -> Unit)? =
        if (isPartial && onFixComponents != null && !selectionMode) {
            {
                TextButton(
                    onClick = onFixComponents,
                    modifier = Modifier.heightIn(min = AppTheme.spacing.spacing48),
                ) {
                    Icon(
                        imageVector = Icons.Default.Build,
                        contentDescription = null,
                        modifier = Modifier.size(AppTheme.dimensions.size14),
                    )
                    Spacer(modifier = Modifier.width(AppTheme.spacing.spacing4))
                    Text(
                        text = "Finish setup",
                        style = AppTheme.typography.labelSmall,
                    )
                }
            }
        } else {
            null
        }

    GenericListItem(
        title = model.modelId,
        eyebrow = model.displayFilename(),
        metadata = metadata,
        emphasized = isSelected,
        modifier = modifier
            .combinedClickable(
                role = if (selectionMode) Role.Checkbox else Role.Button,
                onClick = {
                    when {
                        selectionMode -> onToggleSelect()
                        isSupported -> onOpenModel()
                    }
                },
                onLongClick = onLongPress,
            )
            .semantics {
                contentDescription = rowDescription
                role = if (selectionMode) Role.Checkbox else Role.Button
                if (selectionMode) selected = isSelected
            },
        leading = if (selectionMode) {
            {
                Icon(
                    imageVector = if (isSelected) {
                        Icons.Filled.CheckBox
                    } else {
                        Icons.Outlined.CheckBoxOutlineBlank
                    },
                    contentDescription = null,
                    tint = AppTheme.colors.primary,
                )
            }
        } else {
            null
        },
        status = statusContent,
        trailing = fixComponentsAction,
    )
}

@Composable
private fun LocalModelStatusMark(
    label: String,
    description: String,
    icon: ImageVector,
) {
    StatusMark(
        label = label,
        contentDescription = description,
        icon = icon,
        modifier = Modifier.semantics { contentDescription = description },
    )
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

@Preview(name = "Downloaded rows - compact", widthDp = 360, heightDp = 320)
@Composable
private fun DownloadedListItemCompactPreview() {
    DownloadedListItemPreviewContent()
}

@Preview(name = "Downloaded rows - large text", widthDp = 360, heightDp = 500, fontScale = 2f)
@Composable
private fun DownloadedListItemLargeTextPreview() {
    DownloadedListItemPreviewContent()
}

@Composable
private fun DownloadedListItemPreviewContent() {
    CaraMLTheme(ThemePreferences()) {
        Surface {
            Column {
                DownloadedListItem(
                    model = previewLocalModel(
                        id = 1,
                        filename = "MiniCPM5-2B-Q4_K_M.gguf",
                        status = LocalModelEntity.STATUS_READY,
                    ),
                    selectionMode = false,
                    isSelected = false,
                    onOpenModel = {},
                    onToggleSelect = {},
                    onLongPress = {},
                )
                DownloadedListItem(
                    model = previewLocalModel(
                        id = 2,
                        filename = "MiniCPM5-2B-Q5_K_M.gguf",
                        status = LocalModelEntity.STATUS_PARTIAL,
                    ),
                    selectionMode = false,
                    isSelected = false,
                    onOpenModel = {},
                    onToggleSelect = {},
                    onLongPress = {},
                    onFixComponents = {},
                )
                DownloadedListItem(
                    model = previewLocalModel(
                        id = 3,
                        filename = "MiniCPM5-2B-Q8_0.gguf",
                        status = LocalModelEntity.STATUS_READY,
                    ),
                    selectionMode = true,
                    isSelected = true,
                    onOpenModel = {},
                    onToggleSelect = {},
                    onLongPress = {},
                )
            }
        }
    }
}

private fun previewLocalModel(id: Long, filename: String, status: String) = LocalModelEntity(
    id = id,
    modelId = "openbmb/MiniCPM5-2B-GGUF",
    filename = filename,
    localPath = "/preview/$filename",
    sizeBytes = 2_147_483_648L,
    downloadedAt = 0L,
    author = "openbmb",
    libraryName = null,
    pipelineTag = "text-generation",
    componentStatus = status,
)
