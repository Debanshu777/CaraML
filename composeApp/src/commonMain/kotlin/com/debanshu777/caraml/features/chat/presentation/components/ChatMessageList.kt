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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.debanshu777.caraml.features.chat.data.ChatMessage
import com.debanshu777.caraml.features.chat.presentation.StreamingState
import com.debanshu777.caraml.features.chat.presentation.components.providers.ChatMessageListPreviewProvider
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.StateFlow

@Composable
private fun StreamingMessageBubble(
    message: ChatMessage,
    streamingStateFlow: StateFlow<StreamingState>,
) {
    val streamingState by streamingStateFlow.collectAsStateWithLifecycle()
    val displayText = streamingState.streamingText.takeIf { it.isNotEmpty() } ?: message.text
    MessageBubble(message.copy(text = displayText))
}

@Preview
@Composable
private fun ChatMessageListPreview(
    @PreviewParameter(ChatMessageListPreviewProvider::class) messages: ImmutableList<ChatMessage>
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
    messages: ImmutableList<ChatMessage>,
    listState: LazyListState,
    streamingMessageId: String? = null,
    streamingStateFlow: StateFlow<StreamingState>? = null,
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
                if (message.id == streamingMessageId && streamingStateFlow != null) {
                    StreamingMessageBubble(
                        message = message,
                        streamingStateFlow = streamingStateFlow
                    )
                } else {
                    MessageBubble(message)
                }
            }
        }
    }
}
