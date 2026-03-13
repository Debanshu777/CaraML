package com.debanshu777.caraml.features.chat.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.features.chat.data.ChatMessage
import com.debanshu777.caraml.features.chat.presentation.components.preview.providers.ChatMessageListPreviewProvider

@Preview
@Composable
private fun ChatMessageListPreview(
    @PreviewParameter(ChatMessageListPreviewProvider::class) messages: List<ChatMessage>
) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            ChatMessageList(
                messages = messages,
                listState = rememberLazyListState(),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun ChatMessageList(
    messages: List<ChatMessage>,
    listState: LazyListState,
    streamingMessageId: String? = null,
    streamingText: String = "",
    modifier: Modifier = Modifier
) {
    if (messages.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Send a message to get started",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        LazyColumn(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                items = messages,
                key = { it.id }
            ) { message ->
                val displayMessage = if (message.id == streamingMessageId && streamingText.isNotEmpty()) {
                    message.copy(text = streamingText)
                } else {
                    message
                }
                MessageBubble(displayMessage)
            }
        }
    }
}
