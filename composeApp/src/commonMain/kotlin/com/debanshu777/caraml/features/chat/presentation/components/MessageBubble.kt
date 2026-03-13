package com.debanshu777.caraml.features.chat.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.features.chat.data.ChatMessage
import com.debanshu777.caraml.features.chat.data.MessageRole
import com.debanshu777.caraml.features.chat.presentation.components.preview.providers.ChatMessagePreviewProvider

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
            .padding(vertical = 12.dp),
        horizontalAlignment = alignment
    ) {
        Text(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(backgroundColor)
                .padding(if(isUser)12.dp else 0.dp),
            text = message.text,
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
        )
        if (!isUser && message.inferenceMetrics != null) {
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
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
        horizontalArrangement = Arrangement.spacedBy(2.dp),
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
