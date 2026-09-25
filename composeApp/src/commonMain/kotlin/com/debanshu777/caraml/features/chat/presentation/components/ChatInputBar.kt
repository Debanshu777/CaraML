package com.debanshu777.caraml.features.chat.presentation.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.LocalSpacing
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.core.ui.components.CommandSurface
import com.debanshu777.caraml.core.ui.motion.LocalAuroraMotionPolicy
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatInputBar(
    generationMode: GenerationMode,
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
    var isFocused by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
    val motion = LocalAuroraMotionPolicy.current

    val placeholderText = when (generationMode) {
        GenerationMode.Text -> "How can I help you today?"
        GenerationMode.Image -> "Describe an image"
        GenerationMode.Video -> "Describe a video"
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = LocalSpacing.current.l),
    ) {
        CommandSurface(
            focused = isFocused,
            active = isGenerating,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("create-command"),
            contentPadding = PaddingValues(0.dp),
        ) {
            Column {
                TextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { isFocused = it.isFocused },
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text(placeholderText) },
                    minLines = 1,
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
                            .heightIn(min = 48.dp)
                            .clickable { showModelSheet = true }
                            .semantics {
                                contentDescription = selectedModel?.modelId
                                    ?.substringAfterLast("/")
                                    ?.let { "Select model. Current model $it" }
                                    ?: "Select model"
                            },
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
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    FilledIconButton(
                        onClick = {
                            if (isGenerating) {
                                onCancelGeneration()
                            } else if (inputText.isNotBlank()) {
                                onSendMessage(inputText)
                                inputText = ""
                            }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .semantics {
                                contentDescription = if (isGenerating) {
                                    "Stop generation"
                                } else {
                                    "Send message"
                                }
                            },
                        enabled = isGenerating || inputText.isNotBlank(),
                    ) {
                        Crossfade(
                            targetState = isGenerating,
                            animationSpec = tween(durationMillis = motion.opacityDurationMillis),
                            label = "composer generation icon",
                        ) { generating ->
                            if (generating) {
                                Icon(
                                    imageVector = Icons.Default.Stop,
                                    contentDescription = null,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Default.Send,
                                    contentDescription = null,
                                )
                            }
                        }
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
            topModels = topModels,
            selectedModel = selectedModel,
            onSelectModel = onSelectModel,
            onDownloadModelClick = onDownloadModelClick
        )
    }
}
