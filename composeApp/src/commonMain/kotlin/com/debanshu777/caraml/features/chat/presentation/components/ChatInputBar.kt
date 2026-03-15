package com.debanshu777.caraml.features.chat.presentation.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.features.chat.data.LiveGenerationStats
import com.debanshu777.caraml.features.chat.presentation.StreamingState
import com.debanshu777.caraml.features.chat.presentation.components.providers.LiveGenerationStatsPreviewProvider
import com.debanshu777.caraml.features.chat.presentation.components.providers.LocalModelPreviewProvider
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

@Preview
@Composable
private fun ChatInputBarPreview(
    @PreviewParameter(LocalModelPreviewProvider::class) selectedModel: LocalModelEntity
) {
    MaterialTheme {
        Surface {
            ChatInputBar(
                isGenerating = false,
                selectedModel = selectedModel,
                topModels = persistentListOf(selectedModel),
                onSelectModel = {},
                onDownloadModelClick = {},
                onSendMessage = {},
                onCancelGeneration = {},
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Preview(name = "No Model")
@Composable
private fun ChatInputBarNoModelPreview() {
    MaterialTheme {
        Surface {
            ChatInputBar(
                isGenerating = false,
                selectedModel = null,
                topModels = persistentListOf(),
                onSelectModel = {},
                onDownloadModelClick = {},
                onSendMessage = {},
                onCancelGeneration = {},
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Preview(name = "Generating")
@Composable
private fun ChatInputBarGeneratingPreview() {
    val model = LocalModelPreviewProvider().values.first()
    val stats = LiveGenerationStatsPreviewProvider().values.first()
    MaterialTheme {
        Surface {
            ChatInputBar(
                isGenerating = true,
                selectedModel = model,
                topModels = persistentListOf(model),
                onSelectModel = {},
                onDownloadModelClick = {},
                onSendMessage = {},
                onCancelGeneration = {},
                contextIndicator = {
                    ContextProgressIndicator(
                        contextUsed = stats.contextUsed,
                        contextLimit = stats.contextLimit,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ContextProgressIndicator(
    contextUsed: Int,
    contextLimit: Int,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(expanded) {
        if (expanded) {
            delay(3000)
            expanded = false
        }
    }

    val progress = if (contextLimit > 0) {
        (contextUsed.toFloat() / contextLimit).coerceIn(0f, 1f)
    } else {
        0f
    }
    val percent = if (contextLimit > 0) contextUsed * 100 / contextLimit else 0

    Row(
        modifier = modifier
            .clickable { expanded = !expanded }
            .animateContentSize(animationSpec = tween(300)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CircularProgressIndicator(
            progress = { progress },
            modifier = Modifier.size(24.dp),
            strokeWidth = 2.dp
        )
        if (expanded) {
            Text(
                text = "$contextUsed/$contextLimit ($percent%)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun RowScope.ContextStatsIndicator(
    streamingStateFlow: StateFlow<StreamingState>,
) {
    val state by streamingStateFlow.collectAsStateWithLifecycle()
    val liveStats = state.liveStats
    if (liveStats != null) {
        ContextProgressIndicator(
            contextUsed = liveStats.contextUsed,
            contextLimit = liveStats.contextLimit,
            modifier = Modifier.align(Alignment.CenterVertically)
        )
        Spacer(modifier = Modifier.width(4.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatInputBar(
    isGenerating: Boolean,
    selectedModel: LocalModelEntity?,
    topModels: ImmutableList<LocalModelEntity>,
    onSelectModel: (LocalModelEntity) -> Unit,
    onDownloadModelClick: () -> Unit,
    onSendMessage: (String) -> Unit,
    onCancelGeneration: () -> Unit,
    contextIndicator: @Composable RowScope.() -> Unit = {},
    modifier: Modifier = Modifier
) {
    var inputText by remember { mutableStateOf("") }
    var showModelSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            TextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("How can I help you today?") },
                maxLines = 4,
                enabled = !isGenerating,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                )
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                contextIndicator()
                IconButton(
                    modifier = Modifier.weight(1f),
                    onClick = { }
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add attachment"
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Row(
                    modifier = Modifier.weight(8f).clickable { showModelSheet = true },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        modifier = Modifier.weight(7f),
                        text = selectedModel?.modelId?.substringAfterLast("/") ?: "Select Model",
                        style = MaterialTheme.typography.bodyMedium,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                        maxLines = 1
                    )
                    Icon(
                        modifier = Modifier.weight(1f),
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "Select model"
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                if (isGenerating) {
                    IconButton(
                        modifier = Modifier.weight(1f),
                        onClick = onCancelGeneration,
                        enabled = true
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Stop generating"
                        )
                    }
                } else {
                    IconButton(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (inputText.isNotBlank()) {
                                onSendMessage(inputText)
                                inputText = ""
                            }
                        },
                        enabled = inputText.isNotBlank()
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send message"
                        )
                    }
                }
            }
        }
    }

    if (showModelSheet) {
        ModalBottomSheet(
            onDismissRequest = { showModelSheet = false },
            sheetState = sheetState
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp)
            ) {
                item {
                    Text(
                        text = "Select Model",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }

                items(topModels) { model ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelectModel(model)
                                showModelSheet = false
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
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

                        if (model.id == selectedModel?.id) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }

                if (topModels.isNotEmpty()) {
                    item {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    }
                }

                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showModelSheet = false
                                onDownloadModelClick()
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Download model",
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = "Download model",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}
