package com.debanshu777.caraml.features.chat.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import com.debanshu777.caraml.core.ui.motion.LocalAuroraMotionPolicy
import com.debanshu777.caraml.features.chat.data.ChatMessage
import com.debanshu777.caraml.features.chat.presentation.StreamingState
import com.debanshu777.caraml.features.chat.presentation.components.providers.ChatMessageListPreviewProvider
import kotlinx.collections.immutable.ImmutableList

@Composable
private fun StreamingMessageBubble(
    message: ChatMessage,
    streamingState: StreamingState,
    loadMedia: suspend (String) -> ByteArray?,
) {
    val displayText = streamingState.streamingText.takeIf { it.isNotEmpty() } ?: message.text
    val pendingMedia = streamingState.pendingMediaGeneration &&
        streamingState.streamingMessageId == message.id
    MessageBubble(
        message = message.copy(text = displayText),
        showMediaPending = pendingMedia,
        isStreaming = true,
        streamingThinking = streamingState.streamingThinkingText,
        imageGenStep = streamingState.imageGenStep,
        imageGenTotalSteps = streamingState.imageGenTotalSteps,
        imageGenRequestedSteps = streamingState.imageGenRequestedSteps,
        imageGenElapsedSeconds = streamingState.imageGenElapsedSeconds,
        loadMedia = loadMedia,
    )
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
    streamingState: StreamingState? = null,
    loadMedia: suspend (String) -> ByteArray? = { null },
    modifier: Modifier = Modifier
) {
    val initialMessageIds = remember { messages.mapTo(mutableSetOf()) { it.id } }
    val motion = LocalAuroraMotionPolicy.current
    val insertionOffset = with(LocalDensity.current) { 8.dp.roundToPx() }

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
                val inserted = message.id !in initialMessageIds
                Box(
                    modifier = if (inserted) {
                        Modifier.animateItem(
                            fadeInSpec = tween(motion.opacityDurationMillis),
                            placementSpec = null,
                            fadeOutSpec = null,
                        )
                    } else {
                        Modifier
                    },
                ) {
                    if (inserted) {
                        val visibility = remember(message.id) {
                            MutableTransitionState(false).apply { targetState = true }
                        }
                        AnimatedVisibility(
                            visibleState = visibility,
                            enter = if (motion.spatialTransitionsEnabled) {
                                slideInVertically(
                                    animationSpec = tween(motion.peerTransitionMillis),
                                    initialOffsetY = { insertionOffset },
                                )
                            } else {
                                EnterTransition.None
                            },
                            exit = ExitTransition.None,
                        ) {
                            ChatMessageListItem(
                                message = message,
                                streamingMessageId = streamingMessageId,
                                streamingState = streamingState,
                                loadMedia = loadMedia,
                            )
                        }
                    } else {
                        ChatMessageListItem(
                            message = message,
                            streamingMessageId = streamingMessageId,
                            streamingState = streamingState,
                            loadMedia = loadMedia,
                        )
                    }
                }
            }
            item{
                Spacer(modifier = Modifier.height(150.dp))
            }
    }
}

@Composable
private fun ChatMessageListItem(
    message: ChatMessage,
    streamingMessageId: String?,
    streamingState: StreamingState?,
    loadMedia: suspend (String) -> ByteArray?,
) {
    if (message.id == streamingMessageId && streamingState != null) {
        StreamingMessageBubble(
            message = message,
            streamingState = streamingState,
            loadMedia = loadMedia,
        )
    } else {
        MessageBubble(message, loadMedia = loadMedia)
    }
}
