package com.debanshu777.caraml.features.chat.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.features.chat.presentation.components.ChatInputBar
import com.debanshu777.caraml.features.chat.presentation.components.ContextStatsIndicator
import com.debanshu777.caraml.features.chat.presentation.components.ChatMessageList
import com.debanshu777.caraml.features.chat.presentation.components.GenerationStatsBar
import com.debanshu777.caraml.features.chat.presentation.components.ModelErrorScreen
import com.debanshu777.caraml.features.chat.presentation.components.ModelLoadingScreen
import com.debanshu777.caraml.features.chat.presentation.components.ModelSelectorTopBar
import com.debanshu777.caraml.features.chat.presentation.components.NoModelsScreen

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

    Column(modifier = modifier.fillMaxSize()) {
        ModelSelectorTopBar()

        when (uiState) {
            is ChatUiState.NoModels -> {
                NoModelsScreen(onDownloadModelClick = onNavigateToSearch)
            }

            is ChatUiState.ModelLoading -> {
                ModelLoadingScreen()
            }

            is ChatUiState.ModelError -> {
                ModelErrorScreen(
                    errorMessage = uiState.message,
                    onTryAnotherModelClick = onNavigateToSearch
                )
            }

            is ChatUiState.Ready -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    ChatMessageList(
                        messages = uiState.messages,
                        listState = listState,
                        streamingMessageId = streamingState.streamingMessageId,
                        streamingStateFlow = streamingStateFlow,
                        modifier = Modifier.weight(1f)
                    )

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
    }
}
