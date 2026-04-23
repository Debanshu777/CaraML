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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.LocalSpacing
import com.debanshu777.caraml.core.ui.graphics.decodePngToImageBitmap
import com.debanshu777.caraml.features.chat.data.ChatMessage
import com.debanshu777.caraml.features.chat.data.MessageRole
import com.debanshu777.caraml.features.chat.presentation.components.providers.ChatMessagePreviewProvider
import com.mikepenz.markdown.m3.Markdown

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
    modifier: Modifier = Modifier
) {
    val isUser = message.role == MessageRole.User
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val backgroundColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        Color.Transparent
    }
    val textColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }

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
        if (!isUser && (thinkingText.isNotEmpty() || isStreaming)) {
            ThoughtsDisclosure(
                thinking = thinkingText,
                isStreaming = isStreaming,
                outputIsEmpty = output.isEmpty(),
            )
        }

        if (isUser) {
            if (message.text.isNotEmpty()) {
                Text(
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.medium)
                        .background(backgroundColor)
                        .padding(LocalSpacing.current.m),
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor,
                )
            }
        } else if (output.isNotEmpty()) {
            // Render assistant answer as markdown — always, even mid-stream.
            Markdown(
                content = output,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(backgroundColor)
                    .padding(if (backgroundColor == Color.Transparent) 0.dp else LocalSpacing.current.m),
                typography = chatMarkdownTypography(),
            )
        }

        if (!isUser && showMediaPending) {
            Row(
                modifier = Modifier
                    .padding(top = LocalSpacing.current.s)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    .padding(horizontal = LocalSpacing.current.l, vertical = LocalSpacing.current.m),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.m)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 2.dp
                )
                Text(
                    text = "Generating…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor
                )
            }
        }

        val imageBytes = message.imageBytes
        if (!isUser && imageBytes != null && imageBytes.isNotEmpty()) {
            val bitmap = remember(message.id, imageBytes.size) {
                decodePngToImageBitmap(imageBytes)
            }
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

        val frames = message.videoFrames
        if (!isUser && !frames.isNullOrEmpty()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = LocalSpacing.current.s),
                horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.s)
            ) {
                itemsIndexed(frames, key = { index, _ -> "${message.id}_$index" }) { index, frameBytes ->
                    val frameBitmap = remember(message.id, index, frameBytes.size) {
                        decodePngToImageBitmap(frameBytes)
                    }
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
            enter = expandVertically(animationSpec = tween(180)) + fadeIn(animationSpec = tween(180)),
            exit = shrinkVertically(animationSpec = tween(140)) + fadeOut(animationSpec = tween(140)),
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
