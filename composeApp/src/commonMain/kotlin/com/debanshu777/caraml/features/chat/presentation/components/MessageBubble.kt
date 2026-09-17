package com.debanshu777.caraml.features.chat.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.LocalSpacing
import com.debanshu777.caraml.core.theme.AuroraSurfaceLevel
import com.debanshu777.caraml.core.ui.components.CaraMLPane
import com.debanshu777.caraml.core.ui.graphics.decodePngToImageBitmap
import com.debanshu777.caraml.core.ui.motion.LocalAuroraMotionPolicy
import com.debanshu777.caraml.features.chat.data.ChatMessage
import com.debanshu777.caraml.features.chat.data.MessageRole
import com.debanshu777.caraml.features.chat.presentation.components.providers.ChatMessagePreviewProvider
import com.mikepenz.markdown.m3.Markdown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Preview
@Composable
private fun MessageBubblePreview(
    @PreviewParameter(ChatMessagePreviewProvider::class) message: ChatMessage
) {
    MaterialTheme {
        Surface {
            MessageBubble(message = message, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun MessageBubble(
    message: ChatMessage,
    showMediaPending: Boolean = false,
    isStreaming: Boolean = false,
    streamingThinking: String = "",
    imageGenStep: Int = 0,
    imageGenTotalSteps: Int = 0,
    imageGenRequestedSteps: Int = 0,
    imageGenElapsedSeconds: Int = 0,
    loadMedia: suspend (String) -> ByteArray? = { null },
    modifier: Modifier = Modifier
) {
    val isUser = message.role == MessageRole.User
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val textColor = MaterialTheme.colorScheme.onSurface

    // For assistant messages: prefer the live streamingThinking (only set on the
    // streaming bubble); otherwise fall back to the persisted value. Output is
    // always `message.text` — the streaming list passes the live text through.
    val thinkingText = remember(message.id, streamingThinking, message.thinking) {
        when {
            streamingThinking.isNotBlank() -> streamingThinking
            !message.thinking.isNullOrBlank() -> message.thinking
            else -> ""
        }
    }
    val output = message.text

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = LocalSpacing.current.m),
        horizontalAlignment = alignment
    ) {
        if (!isUser && (thinkingText.isNotEmpty() || (isStreaming && !showMediaPending))) {
            ThoughtsDisclosure(
                thinking = thinkingText,
                isStreaming = isStreaming && !showMediaPending,
                outputIsEmpty = output.isEmpty(),
            )
        }

        if (isUser) {
            if (message.text.isNotEmpty()) {
                CaraMLPane(
                    level = AuroraSurfaceLevel.Pane,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        modifier = Modifier.padding(LocalSpacing.current.m),
                        text = message.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor,
                    )
                }
            }
        } else if (output.isNotEmpty()) {
            val outputModifier = Modifier
                .fillMaxWidth()
            if (isStreaming) {
                Text(
                    text = output,
                    modifier = outputModifier,
                    style = MaterialTheme.typography.bodyLarge,
                    color = textColor,
                )
            } else {
                Markdown(
                    content = output,
                    modifier = outputModifier,
                    typography = chatMarkdownTypography(),
                )
            }
        }

        if (!isUser && showMediaPending) {
            // Three phases:
            //   1. Preparing — sampler hasn't reported any step yet (text encoding, latent prep)
            //   2. Sampling — sampler is reporting step/total
            //   3. Finalizing — sampler reached total but image hasn't decoded yet (rare)
            val isSampling = imageGenTotalSteps > 0 && imageGenStep > 0
            val isFinalizing = imageGenTotalSteps > 0 && imageGenStep >= imageGenTotalSteps
            val elapsed = imageGenElapsedSeconds

            val statusText = when {
                isFinalizing -> "Finalizing local output · ${elapsed}s"
                isSampling   -> "Step $imageGenStep / $imageGenTotalSteps · ${elapsed}s"
                else         -> if (imageGenRequestedSteps > 0)
                                    "Preparing local generation · " +
                                        "$imageGenRequestedSteps planned steps · ${elapsed}s"
                                else "Preparing local generation · ${elapsed}s"
            }

            val phase = when {
                isFinalizing -> GenerationActivityPhase.Finalizing
                isSampling -> GenerationActivityPhase.Generating
                else -> GenerationActivityPhase.Preparing
            }

            GenerationActivity(
                label = statusText,
                progress = if (isSampling) {
                    imageGenStep.toFloat() / imageGenTotalSteps
                } else {
                    null
                },
                phase = phase,
                modifier = Modifier
                    .padding(top = LocalSpacing.current.s)
                    .fillMaxWidth(),
            )
        }

        if (!isUser && (message.imagePath != null || message.imageBytes?.isNotEmpty() == true)) {
            val bitmap = rememberDecodedMediaBitmap(
                key = message.id,
                path = message.imagePath,
                inlineBytes = message.imageBytes,
                loadMedia = loadMedia,
            )
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "Generated image",
                    modifier = Modifier
                        .padding(top = LocalSpacing.current.s)
                        .heightIn(max = 320.dp)
                        .clip(MaterialTheme.shapes.medium),
                    contentScale = ContentScale.Fit
                )
            }
        }

        val frameSources = remember(message.videoFramePaths, message.videoFrames) {
            when {
                !message.videoFramePaths.isNullOrEmpty() ->
                    message.videoFramePaths.map { path -> path to null }
                !message.videoFrames.isNullOrEmpty() ->
                    message.videoFrames.map { bytes -> null to bytes }
                else -> emptyList()
            }
        }
        if (!isUser && frameSources.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = LocalSpacing.current.s),
                horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.s)
            ) {
                itemsIndexed(frameSources, key = { index, _ -> "${message.id}_$index" }) {
                        index, (framePath, frameBytes) ->
                    val frameBitmap = rememberDecodedMediaBitmap(
                        key = "${message.id}_$index",
                        path = framePath,
                        inlineBytes = frameBytes,
                        loadMedia = loadMedia,
                    )
                    if (frameBitmap != null) {
                        Image(
                            bitmap = frameBitmap,
                            contentDescription = "Generated video frame ${index + 1}",
                            modifier = Modifier
                                .size(120.dp)
                                .clip(MaterialTheme.shapes.small),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            }
        }

        if (!isUser && message.inferenceMetrics != null) {
            Row(
                modifier = Modifier.padding(top = LocalSpacing.current.s),
                horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.s)
            ) {
                Text(
                    text = "Statistics:",
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.5f)
                )

                val tokensPerSec =
                    ((message.inferenceMetrics.tokensPerSecond * 100).toInt() / 100.0)
                StatItem(
                    icon = Icons.Default.Speed,
                    text = "$tokensPerSec tokens/s",
                    textColor = textColor
                )

                StatItem(
                    icon = Icons.Default.DataUsage,
                    text = "${message.inferenceMetrics.tokenCount} tokens",
                    textColor = textColor
                )

                val timeSec = ((message.inferenceMetrics.generationTimeMs / 10.0).toInt() / 100.0)
                StatItem(
                    icon = Icons.Default.AccessTime,
                    text = "${timeSec}s",
                    textColor = textColor
                )
            }
        }
    }
}

@Composable
private fun rememberDecodedMediaBitmap(
    key: String,
    path: String?,
    inlineBytes: ByteArray?,
    loadMedia: suspend (String) -> ByteArray?,
): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = key,
        key2 = path,
        key3 = inlineBytes,
    ) {
        val encoded = when {
            path != null -> loadMedia(path)
            inlineBytes?.isNotEmpty() == true -> inlineBytes
            else -> null
        }
        value = encoded?.let { bytes ->
            withContext(Dispatchers.Default) { decodePngToImageBitmap(bytes) }
        }
    }
    return bitmap
}

/**
 * Collapsible "Thoughts" panel that surfaces a model's `<think>...</think>` block.
 *
 * Auto-expansion follows: `isStreaming || outputIsEmpty`. Once the answer starts
 * arriving (and we're done streaming), it auto-collapses. The user can pin it
 * open or shut for the lifetime of the message via the local override.
 */
@Composable
private fun ThoughtsDisclosure(
    thinking: String,
    isStreaming: Boolean,
    outputIsEmpty: Boolean,
    modifier: Modifier = Modifier,
) {
    val motion = LocalAuroraMotionPolicy.current
    var override by remember { mutableStateOf<Boolean?>(null) }
    val autoExpanded = isStreaming || outputIsEmpty
    val expanded = override ?: autoExpanded
    val showSpinner = isStreaming && outputIsEmpty

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = LocalSpacing.current.s)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable { override = !expanded }
                .padding(horizontal = LocalSpacing.current.s, vertical = LocalSpacing.current.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.s),
        ) {
            if (showSpinner) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 1.5.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Psychology,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = if (showSpinner) "Thinking…" else "Thoughts",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse thoughts" else "Expand thoughts",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AnimatedVisibility(
            visible = expanded && thinking.isNotEmpty(),
            enter = if (motion.spatialTransitionsEnabled) {
                expandVertically(animationSpec = tween(motion.peerTransitionMillis)) +
                    fadeIn(animationSpec = tween(motion.opacityDurationMillis))
            } else {
                fadeIn(animationSpec = tween(motion.opacityDurationMillis))
            },
            exit = if (motion.spatialTransitionsEnabled) {
                shrinkVertically(animationSpec = tween(motion.exitMillis)) +
                    fadeOut(animationSpec = tween(motion.exitMillis))
            } else {
                fadeOut(animationSpec = tween(motion.opacityDurationMillis))
            },
        ) {
            Text(
                text = thinking,
                style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = LocalSpacing.current.xs)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(LocalSpacing.current.m),
            )
        }
    }
}

@Composable
private fun StatItem(
    icon: ImageVector,
    text: String,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.xxs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = textColor.copy(alpha = 0.5f),
            modifier = Modifier.size(12.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = textColor.copy(alpha = 0.5f)
        )
    }
}
