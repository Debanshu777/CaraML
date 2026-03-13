package com.debanshu777.caraml.features.chat.presentation.components.preview.providers

import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.debanshu777.caraml.features.chat.data.ChatMessage
import com.debanshu777.caraml.features.chat.data.InferenceMetrics
import com.debanshu777.caraml.features.chat.data.MessageRole

class ChatMessagePreviewProvider : PreviewParameterProvider<ChatMessage> {
    override val values: Sequence<ChatMessage> = sequenceOf(
        ChatMessage(
            id = "short-user",
            role = MessageRole.User,
            text = "Hello!"
        ),
        ChatMessage(
            id = "long-user",
            role = MessageRole.User,
            text = "This is a very long message that tests how the UI handles " +
                "multiple lines of text. It contains several paragraphs to simulate " +
                "real-world usage where users might paste or type lengthy content. " +
                "We want to ensure proper wrapping and layout. Lorem ipsum dolor sit " +
                "amet, consectetur adipiscing elit. Sed do eiusmod tempor incididunt " +
                "ut labore et dolore magna aliqua. Ut enim ad minim veniam."
        ),
        ChatMessage(
            id = "assistant-no-metrics",
            role = MessageRole.Assistant,
            text = "Here is a simple assistant response without inference metrics."
        ),
        ChatMessage(
            id = "assistant-with-metrics",
            role = MessageRole.Assistant,
            text = "This response includes inference statistics for token count and generation speed.",
            inferenceMetrics = InferenceMetrics(
                tpotMs = 25.0,
                tokenCount = 42,
                generationTimeMs = 1050
            )
        ),
        ChatMessage(
            id = "special-chars",
            role = MessageRole.User,
            text = "Special chars: 日本語 🎉 émojis & symbols! 你好世界"
        ),
        ChatMessage(
            id = "code-block",
            role = MessageRole.Assistant,
            text = "```kotlin\nfun main() {\n    println(\"Hello World\")\n}\n```\n\nHere's some code."
        ),
        ChatMessage(
            id = "empty-text",
            role = MessageRole.User,
            text = ""
        ),
    )
}
