package com.debanshu777.caraml.features.chat.presentation.components.preview.providers

import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.debanshu777.caraml.features.chat.data.ChatMessage
import com.debanshu777.caraml.features.chat.data.InferenceMetrics
import com.debanshu777.caraml.features.chat.data.MessageRole

class ChatMessageListPreviewProvider : PreviewParameterProvider<List<ChatMessage>> {
    override val values: Sequence<List<ChatMessage>> = sequenceOf(
        emptyList(),
        listOf(
            ChatMessage(
                id = "1",
                role = MessageRole.User,
                text = "Hello!"
            ),
            ChatMessage(
                id = "2",
                role = MessageRole.Assistant,
                text = "Hi! How can I help you today?",
                inferenceMetrics = InferenceMetrics(
                    tpotMs = 25.0,
                    tokenCount = 8,
                    generationTimeMs = 200
                )
            )
        ),
        listOf(
            ChatMessage(id = "1", role = MessageRole.User, text = "Short"),
            ChatMessage(id = "2", role = MessageRole.Assistant, text = "Reply 1", inferenceMetrics = InferenceMetrics(20.0, 5, 100)),
            ChatMessage(id = "3", role = MessageRole.User, text = "Another question with more text to test layout"),
            ChatMessage(id = "4", role = MessageRole.Assistant, text = "A longer assistant response that spans multiple lines to verify proper rendering of extended content in the chat interface.", inferenceMetrics = InferenceMetrics(30.0, 25, 750)),
            ChatMessage(id = "5", role = MessageRole.User, text = "Thanks!")
        ),
    )
}
