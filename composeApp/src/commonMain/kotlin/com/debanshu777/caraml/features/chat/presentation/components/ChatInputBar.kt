package com.debanshu777.caraml.features.chat.presentation.components

import com.debanshu777.caraml.core.ui.icons.AppIcons
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import com.debanshu777.caraml.core.ui.components.BrandButton
import com.debanshu777.caraml.core.ui.components.BrandButtonStyle
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.AppTheme
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
                    Spacer(modifier = Modifier.width(AppTheme.spacing.spacing4))
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
    modifier: Modifier = Modifier,
    draftText: String? = null,
    onDraftTextChange: ((String) -> Unit)? = null,
    isConversation: Boolean = false,
    inputFocusRequester: FocusRequester? = null,
    onInputAttachmentChanged: (Boolean) -> Unit = {},
    onInputFocusChanged: (Boolean) -> Unit = {},
) {
    val currentOnInputAttachmentChanged by rememberUpdatedState(onInputAttachmentChanged)
    val currentOnInputFocusChanged by rememberUpdatedState(onInputFocusChanged)
    DisposableEffect(Unit) {
        onDispose {
            currentOnInputAttachmentChanged(false)
            currentOnInputFocusChanged(false)
        }
    }
    var localDraft by remember { mutableStateOf("") }
    val inputText = draftText ?: localDraft
    val updateDraft: (String) -> Unit = { value ->
        localDraft = value
        onDraftTextChange?.invoke(value)
    }
    var showModelSheet by remember { mutableStateOf(false) }
    var isFocused by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
    val motion = LocalAuroraMotionPolicy.current

    val placeholderText = when (generationMode) {
        GenerationMode.Text -> if (isConversation) "One more little thought…" else "A tiny astronaut opens a noodle shop…"
        GenerationMode.Image -> "A tiny noodle shop on the moon, in clay…"
        GenerationMode.Video -> "A tiny astronaut flips a noodle in slow motion…"
    }

    Box(
        modifier = modifier
            .fillMaxWidth(),
    ) {
        CommandSurface(
            focused = isFocused,
            active = isGenerating,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("create-command"),
            contentPadding = PaddingValues(AppTheme.dimensions.size0),
        ) {
            Column {
                TextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Keep conversation output visible at large text sizes. TextField
                        // scrolls the complete draft within this viewport without truncating it.
                        .heightIn(
                            min = if (isConversation) 64.dp else 90.dp,
                            max = if (isConversation) 128.dp else Dp.Infinity,
                        )
                        .then(inputFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                        .onFocusChanged {
                            isFocused = it.isFocused
                            currentOnInputFocusChanged(it.isFocused)
                        }
                        .onGloballyPositioned { currentOnInputAttachmentChanged(true) },
                    value = inputText,
                    onValueChange = updateDraft,
                    textStyle = AppTheme.typography.bodyLarge,
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
                        start = AppTheme.spacing.spacing16,
                        end = AppTheme.spacing.spacing12,
                        bottom = AppTheme.spacing.spacing12
                    ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BrandButton(
                        onClick = { showModelSheet = true },
                        style = BrandButtonStyle.Secondary,
                        contentPadding = PaddingValues(horizontal = AppTheme.spacing.spacing8),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = AppTheme.spacing.spacing48)
                            .semantics {
                                contentDescription = selectedModel?.modelId
                                    ?.substringAfterLast("/")
                                    ?.let { "Select model. Current model $it" }
                                    ?: "Select model"
                            },
                    ) {
                        Text(
                            modifier = Modifier.weight(1f, fill = false),
                            text = selectedModel?.modelId?.substringAfterLast("/") ?: "Select model",
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Start,
                            maxLines = 1
                        )
                        Icon(
                            imageVector = AppIcons.ChevronDown,
                            contentDescription = null,
                            modifier = Modifier.size(AppTheme.spacing.spacing24)
                        )
                    }

                    if (generationMode == GenerationMode.Text) {
                        contextIndicator()
                    }
                    Spacer(Modifier.width(8.dp))
                    BrandButton(
                        onClick = {
                            if (isGenerating) {
                                onCancelGeneration()
                            } else if (inputText.isNotBlank()) {
                                onSendMessage(inputText)
                                updateDraft("")
                            }
                        },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .semantics {
                                contentDescription = if (isGenerating) {
                                    "Stop generation"
                                } else {
                                    "Send message"
                                }
                            },
                        enabled = isGenerating || inputText.isNotBlank(),
                        contentPadding = PaddingValues(horizontal = 13.dp, vertical = 10.dp),
                        style = if (isGenerating) BrandButtonStyle.Secondary else BrandButtonStyle.Primary,
                    ) {
                        Text(
                            text = if (isGenerating) "Stop" else if (isConversation) "Send" else "Let’s go",
                        )
                        Spacer(Modifier.width(7.dp))
                        Crossfade(
                            targetState = isGenerating,
                            animationSpec = tween(durationMillis = motion.opacityDurationMillis),
                            label = "composer generation icon",
                        ) { generating ->
                            if (generating) {
                                Icon(
                                    imageVector = AppIcons.Stop,
                                    contentDescription = null,
                                    modifier = Modifier.size(17.dp),
                                )
                            } else {
                                Icon(
                                    imageVector = AppIcons.Send,
                                    contentDescription = null,
                                    modifier = Modifier.size(17.dp),
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
