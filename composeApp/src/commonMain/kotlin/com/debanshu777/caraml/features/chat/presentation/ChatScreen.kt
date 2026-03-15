package com.debanshu777.caraml.features.chat.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.features.chat.presentation.components.providers.ChatMessageListPreviewProvider
import com.debanshu777.caraml.features.chat.presentation.components.providers.LiveGenerationStatsPreviewProvider
import com.debanshu777.caraml.features.chat.presentation.components.providers.LocalModelPreviewProvider
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.StateFlow
import com.debanshu777.caraml.features.chat.presentation.components.ChatInputBar
import com.debanshu777.caraml.features.chat.presentation.components.ContextStatsIndicator
import com.debanshu777.caraml.features.chat.presentation.components.ChatMessageList
import com.debanshu777.caraml.features.chat.presentation.components.GenerationStatsBar
import com.debanshu777.caraml.features.chat.presentation.components.ModelErrorScreen
import com.debanshu777.caraml.features.chat.presentation.components.ModelLoadingScreen
import com.debanshu777.caraml.features.chat.presentation.components.ModelSelectorTopBar
import com.debanshu777.caraml.features.chat.presentation.components.NoModelsScreen
import kotlinx.collections.immutable.toImmutableList

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onNavigateToSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val streamingState by viewModel.streamingState.collectAsStateWithLifecycle()

    ChatScreenContent(
        uiState = uiState,
        streamingState = streamingState,
        streamingStateFlow = viewModel.streamingState,
        onSelectModel = viewModel::selectModel,
        onSendMessage = viewModel::sendMessage,
        onCancelGeneration = viewModel::cancelGeneration,
        onNavigateToSearch = onNavigateToSearch,
        contextIndicator = {
            ContextStatsIndicator(streamingStateFlow = viewModel.streamingState)
        },
        modifier = modifier
    )
}

@Composable
fun ChatScreenContent(
    uiState: ChatUiState,
    streamingState: StreamingState,
    streamingStateFlow: StateFlow<StreamingState>? = null,
    onSelectModel: (LocalModelEntity) -> Unit,
    onSendMessage: (String) -> Unit,
    onCancelGeneration: () -> Unit,
    onNavigateToSearch: () -> Unit,
    contextIndicator: @Composable RowScope.() -> Unit = {},
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    val messageCount = (uiState as? ChatUiState.Ready)?.messages?.size ?: 0
    LaunchedEffect(messageCount) {
        if (messageCount > 0) {
            listState.animateScrollToItem(messageCount - 1)
        }
    }
    Scaffold(
        topBar = { ModelSelectorTopBar() },
        bottomBar = {
            if (uiState is ChatUiState.Ready) {
                Column {
                    if (uiState.isGenerating && streamingState.liveStats != null) {
                        GenerationStatsBar(stats = streamingState.liveStats)
                    }

                    ChatInputBar(
                        isGenerating = uiState.isGenerating,
                        selectedModel = uiState.selectedModel,
                        topModels = uiState.topModels,
                        onSelectModel = onSelectModel,
                        onDownloadModelClick = onNavigateToSearch,
                        onSendMessage = onSendMessage,
                        onCancelGeneration = onCancelGeneration,
                        contextIndicator = contextIndicator
                    )
                }
            }
        }
    ) { paddingValues ->
        when (uiState) {
            is ChatUiState.NoModels -> {
                NoModelsScreen(
                    onDownloadModelClick = onNavigateToSearch,
                    Modifier.padding(paddingValues)
                )
            }

            is ChatUiState.ModelLoading -> {
                ModelLoadingScreen(Modifier.padding(paddingValues))
            }

            is ChatUiState.ModelError -> {
                ModelErrorScreen(
                    errorMessage = uiState.message,
                    onTryAnotherModelClick = onNavigateToSearch,
                    Modifier.padding(paddingValues)
                )
            }

            is ChatUiState.Ready -> {
                ChatMessageList(
                    messages = uiState.messages,
                    listState = listState,
                    streamingMessageId = streamingState.streamingMessageId,
                    streamingStateFlow = streamingStateFlow,
                    modifier = Modifier.fillMaxSize()
                        .padding(
                            top = paddingValues.calculateTopPadding(),
                        )
                )
            }
        }
    }
}

@Preview(name = "NoModels")
@Composable
private fun ChatScreenContentNoModelsPreview() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            ChatScreenContent(
                uiState = ChatUiState.NoModels,
                streamingState = StreamingState(),
                onSelectModel = {},
                onSendMessage = {},
                onCancelGeneration = {},
                onNavigateToSearch = {},
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Preview(name = "ModelLoading")
@Composable
private fun ChatScreenContentModelLoadingPreview() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            ChatScreenContent(
                uiState = ChatUiState.ModelLoading,
                streamingState = StreamingState(),
                onSelectModel = {},
                onSendMessage = {},
                onCancelGeneration = {},
                onNavigateToSearch = {},
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Preview(name = "ModelError")
@Composable
private fun ChatScreenContentModelErrorPreview() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            ChatScreenContent(
                uiState = ChatUiState.ModelError(message = "Failed to load model"),
                streamingState = StreamingState(),
                onSelectModel = {},
                onSendMessage = {},
                onCancelGeneration = {},
                onNavigateToSearch = {},
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Preview(name = "Ready", widthDp = 360, heightDp = 800)
@Composable
private fun ChatScreenContentReadyPreview() {
    val model = LocalModelPreviewProvider().values.elementAt(0)
    val messages = ChatMessageListPreviewProvider().values.elementAt(1)
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            ChatScreenContent(
                uiState = ChatUiState.Ready(
                    messages = messages.toImmutableList(),
                    selectedModel = model,
                    topModels = persistentListOf(model),
                    isGenerating = false
                ),
                streamingState = StreamingState(),
                onSelectModel = {},
                onSendMessage = {},
                onCancelGeneration = {},
                onNavigateToSearch = {},
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Preview(name = "Ready Generating", widthDp = 360, heightDp = 800)
@Composable
private fun ChatScreenContentReadyGeneratingPreview() {
    val model = LocalModelPreviewProvider().values.elementAt(0)
    val messages = ChatMessageListPreviewProvider().values.elementAt(1)
    val liveStats = LiveGenerationStatsPreviewProvider().values.elementAt(0)
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            ChatScreenContent(
                uiState = ChatUiState.Ready(
                    messages = messages.toImmutableList(),
                    selectedModel = model,
                    topModels = persistentListOf(model),
                    isGenerating = true
                ),
                streamingState = StreamingState(
                    liveStats = liveStats,
                ),
                onSelectModel = {},
                onSendMessage = {},
                onCancelGeneration = {},
                onNavigateToSearch = {},
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
