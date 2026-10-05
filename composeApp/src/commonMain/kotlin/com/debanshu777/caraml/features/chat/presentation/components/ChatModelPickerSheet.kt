package com.debanshu777.caraml.features.chat.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.core.storage.localmodel.displayFilename
import com.debanshu777.caraml.core.theme.AuroraSurfaceLevel
import com.debanshu777.caraml.features.chat.domain.GenerationMode
import com.debanshu777.caraml.features.chat.domain.filterForMode
import kotlinx.collections.immutable.ImmutableList

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatModelPickerSheet(
    sheetState: SheetState,
    onDismiss: () -> Unit,
    generationMode: GenerationMode,
    topModels: ImmutableList<LocalModelEntity>,
    selectedModel: LocalModelEntity?,
    onSelectModel: (LocalModelEntity) -> Unit,
    onDownloadModelClick: () -> Unit,
) {
    val pickerModels = topModels.filterForMode(generationMode)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = AppTheme.shapes.extraLarge,
        containerColor = AuroraSurfaceLevel.Floating.containerColor(AppTheme.colors),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppTheme.spacing.spacing16)
                .padding(bottom = AppTheme.spacing.spacing32)
        ) {
            item {
                Text(
                    text = "Select Model",
                    style = AppTheme.typography.headingBase,
                    modifier = Modifier.padding(vertical = AppTheme.spacing.spacing16)
                )
            }

            items(pickerModels) { model ->
                val isSelected = model.id == selectedModel?.id
                ListItem(
                    headlineContent = {
                        Text(
                            text = model.modelId.substringAfterLast("/"),
                            style = AppTheme.typography.bodyLarge
                        )
                    },
                    supportingContent = {
                        Text(
                            text = model.displayFilename(),
                            style = AppTheme.typography.bodySmall,
                            color = AppTheme.colors.onSurfaceVariant
                        )
                    },
                    trailingContent = {
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = AppTheme.actionColor,
                                modifier = Modifier.size(AppTheme.spacing.spacing24)
                            )
                        }
                    },
                    modifier = Modifier.clickable {
                        onSelectModel(model)
                        onDismiss()
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = Color.Transparent,
                    )
                )
            }

            if (pickerModels.isNotEmpty()) {
                item {
                    HorizontalDivider(modifier = Modifier.padding(vertical = AppTheme.spacing.spacing8))
                }
            }

            item {
                ListItem(
                    headlineContent = {
                        Text(
                            text = "Download model",
                            style = AppTheme.typography.bodyLarge
                        )
                    },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Download model",
                            modifier = Modifier.size(AppTheme.spacing.spacing24)
                        )
                    },
                    modifier = Modifier.clickable {
                        onDismiss()
                        onDownloadModelClick()
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = Color.Transparent,
                    )
                )
            }

            item {
                Spacer(modifier = Modifier.height(AppTheme.spacing.spacing16))
            }
        }
    }
}
