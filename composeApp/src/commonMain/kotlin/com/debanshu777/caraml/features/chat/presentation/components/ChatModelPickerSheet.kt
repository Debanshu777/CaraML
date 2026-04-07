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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.core.storage.localmodel.displayFilename
import com.debanshu777.caraml.features.chat.domain.GenerationMode
import com.debanshu777.caraml.features.chat.domain.filterForMode
import kotlinx.collections.immutable.ImmutableList

private val ModeSegmentCount = 3

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatModelPickerSheet(
    sheetState: SheetState,
    onDismiss: () -> Unit,
    generationMode: GenerationMode,
    onGenerationModeChange: (GenerationMode) -> Unit,
    topModels: ImmutableList<LocalModelEntity>,
    selectedModel: LocalModelEntity?,
    onSelectModel: (LocalModelEntity) -> Unit,
    onDownloadModelClick: () -> Unit,
) {
    val modes = remember {
        listOf(
            GenerationMode.Text to "Chat",
            GenerationMode.Image to "Image",
            GenerationMode.Video to "Video"
        )
    }
    var sheetMode by remember { mutableStateOf(generationMode) }
    val selectedModeIndex = modes.indexOfFirst { it.first == sheetMode }.coerceAtLeast(0)
    val pickerModels = topModels.filterForMode(sheetMode)

    fun finishSheet() {
        onGenerationModeChange(sheetMode)
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = { finishSheet() },
        sheetState = sheetState
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            item {
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 8.dp)
                ) {
                    modes.forEachIndexed { index, (mode, label) ->
                        SegmentedButton(
                            selected = index == selectedModeIndex,
                            onClick = { sheetMode = mode },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = ModeSegmentCount
                            )
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    text = "Select Model",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }

            items(pickerModels) { model ->
                val isSelected = model.id == selectedModel?.id
                ListItem(
                    headlineContent = {
                        Text(
                            text = model.modelId.substringAfterLast("/"),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    },
                    supportingContent = {
                        Text(
                            text = model.displayFilename(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingContent = {
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    },
                    modifier = Modifier.clickable {
                        onGenerationModeChange(sheetMode)
                        onSelectModel(model)
                        onDismiss()
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                )
            }

            if (pickerModels.isNotEmpty()) {
                item {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }
            }

            item {
                ListItem(
                    headlineContent = {
                        Text(
                            text = "Download model",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Download model",
                            modifier = Modifier.size(24.dp)
                        )
                    },
                    modifier = Modifier.clickable {
                        finishSheet()
                        onDownloadModelClick()
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                )
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}
