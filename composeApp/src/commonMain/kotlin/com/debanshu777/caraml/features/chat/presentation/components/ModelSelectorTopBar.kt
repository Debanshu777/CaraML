package com.debanshu777.caraml.features.chat.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectorTopBar(
    selectedModel: LocalModelEntity?,
    topModels: List<LocalModelEntity>,
    onSelectModel: (LocalModelEntity) -> Unit,
    onDownloadModelClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showModelDropdown by remember { mutableStateOf(false) }

    TopAppBar(
        modifier = modifier,
        title = {
            Box {
                Row(
                    modifier = Modifier.clickable { showModelDropdown = true },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = selectedModel?.modelId?.substringAfterLast("/") ?: "Select Model",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "Select model"
                    )
                }
                DropdownMenu(
                    expanded = showModelDropdown,
                    onDismissRequest = { showModelDropdown = false }
                ) {
                    topModels.forEach { model ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        text = model.modelId.substringAfterLast("/"),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = model.filename,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                onSelectModel(model)
                                showModelDropdown = false
                            }
                        )
                    }
                    if (topModels.isNotEmpty()) {
                        HorizontalDivider()
                    }
                    DropdownMenuItem(
                        text = { Text("Download model") },
                        onClick = {
                            showModelDropdown = false
                            onDownloadModelClick()
                        }
                    )
                }
            }
        }
    )
}
