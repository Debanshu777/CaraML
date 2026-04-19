package com.debanshu777.caraml.features.chat.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import com.debanshu777.caraml.core.theme.LocalSpacing
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.features.chat.domain.GenerationMode
import com.debanshu777.caraml.features.chat.presentation.components.providers.LiveGenerationStatsPreviewProvider
import com.debanshu777.caraml.features.chat.presentation.components.providers.LocalModelPreviewProvider
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@Preview
@Composable
private fun ChatInputBarPreview(
    @PreviewParameter(LocalModelPreviewProvider::class) selectedModel: LocalModelEntity
) {
    MaterialTheme {
        Surface {
            ChatInputBar(
                generationMode = GenerationMode.Text,
                onGenerationModeChange = {},
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
                generationMode = GenerationMode.Text,
                onGenerationModeChange = {},
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
                generationMode = GenerationMode.Text,
                onGenerationModeChange = {},
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

@Preview(name = "Image mode")
@Composable
private fun ChatInputBarImagePreview() {
    val diffusionModel = LocalModelPreviewProvider().values.first { it.id == 4L }
    MaterialTheme {
        Surface {
            ChatInputBar(
                generationMode = GenerationMode.Image,
                onGenerationModeChange = {},
                isGenerating = false,
                selectedModel = diffusionModel,
                topModels = persistentListOf(diffusionModel),
                onSelectModel = {},
                onDownloadModelClick = {},
                onSendMessage = {},
                onCancelGeneration = {},
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Preview(name = "Video mode")
@Composable
private fun ChatInputBarVideoPreview() {
    val videoModel = LocalModelPreviewProvider().values.first { it.id == 5L }
    MaterialTheme {
        Surface {
            ChatInputBar(
                generationMode = GenerationMode.Video,
                onGenerationModeChange = {},
                isGenerating = false,
                selectedModel = videoModel,
                topModels = persistentListOf(videoModel),
                onSelectModel = {},
                onDownloadModelClick = {},
                onSendMessage = {},
                onCancelGeneration = {},
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * Compact mode badge for the composer: uses [LocalTextStyle] so line height matches the field text
 * and placeholder; the TextField prefix row centers this with the input line vertically.
 */
@Composable
private fun ComposerGenerationModeChip(
    label: String,
    modifier: Modifier = Modifier
) {
    val style = LocalTextStyle.current
    Surface(
        modifier = modifier.padding(end = LocalSpacing.current.s),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.38f)
        ),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 0.dp),
            style = style,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Clip
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatInputBar(
    generationMode: GenerationMode,
    onGenerationModeChange: (GenerationMode) -> Unit,
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

    val placeholderText = when (generationMode) {
        GenerationMode.Text -> "How can I help you today?"
        GenerationMode.Image -> "Describe an image"
        GenerationMode.Video -> "Describe a video"
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = LocalSpacing.current.l, end = LocalSpacing.current.l, bottom = LocalSpacing.current.l),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp
    ) {
        Column {
            TextField(
                modifier = Modifier.fillMaxWidth(),
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = { Text(placeholderText) },
                minLines = 1,
                maxLines = 4,
                enabled = !isGenerating,
                prefix = {
                    when (generationMode) {
                        GenerationMode.Image -> ComposerGenerationModeChip(label = "Image")
                        GenerationMode.Video -> ComposerGenerationModeChip(label = "Video")
                        else -> {}
                    }
                },
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
                modifier = Modifier.fillMaxWidth().padding(
                    start = LocalSpacing.current.l,
                    end = LocalSpacing.current.xs,
                    bottom = LocalSpacing.current.xs
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if(generationMode == GenerationMode.Text) {
                    contextIndicator()
                }

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { showModelSheet = true }
                        .padding(vertical = LocalSpacing.current.s),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    Spacer(modifier= Modifier.weight(0.5f))
                    Text(
                        modifier = Modifier.weight(1f, fill = false),
                        text = selectedModel?.modelId?.substringAfterLast("/") ?: "Select model",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                        maxLines = 1
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "Select model",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }

                if (isGenerating) {
                    FilledIconButton(
                        onClick = onCancelGeneration
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Stop generating"
                        )
                    }
                } else {
                    FilledIconButton(
                        onClick = {
                            if (inputText.isNotBlank()) {
                                onSendMessage(inputText)
                                inputText = ""
                            }
                        },
                        enabled = inputText.isNotBlank()
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Default.Send,
                            contentDescription = "Send message"
                        )
                    }
                }
            }
        }
    }

    if (showModelSheet) {
        ChatModelPickerSheet(
            sheetState = sheetState,
            onDismiss = { showModelSheet = false },
            generationMode = generationMode,
            onGenerationModeChange = onGenerationModeChange,
            topModels = topModels,
            selectedModel = selectedModel,
            onSelectModel = onSelectModel,
            onDownloadModelClick = onDownloadModelClick
        )
    }
}
