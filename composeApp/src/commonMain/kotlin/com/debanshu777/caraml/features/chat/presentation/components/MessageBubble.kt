package com.debanshu777.caraml.features.chat.presentation.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.LocalSpacing
import com.debanshu777.caraml.core.ui.graphics.decodePngToImageBitmap
import com.debanshu777.caraml.features.chat.data.ChatMessage
import com.debanshu777.caraml.features.chat.data.MessageRole
import com.debanshu777.caraml.features.chat.presentation.components.providers.ChatMessagePreviewProvider

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

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = LocalSpacing.current.m),
        horizontalAlignment = alignment
    ) {
        if (message.text.isNotEmpty() || isUser) {
            Text(
                modifier = Modifier
                    .clip(MaterialTheme.shapes.medium)
                    .background(backgroundColor)
                    .padding(if (isUser) LocalSpacing.current.m else 0.dp),
                text = message.text,
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
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
