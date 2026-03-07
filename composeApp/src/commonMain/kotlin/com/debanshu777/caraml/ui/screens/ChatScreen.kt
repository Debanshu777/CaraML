package com.debanshu777.caraml.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.debanshu777.caraml.storage.localModel.LocalModelEntity
import com.debanshu777.caraml.ui.model.ChatMessage
import com.debanshu777.caraml.ui.screens.chat.ChatInputBar
import com.debanshu777.caraml.ui.screens.chat.ChatMessageList
import com.debanshu777.caraml.ui.screens.chat.ModelErrorScreen
import com.debanshu777.caraml.ui.screens.chat.ModelLoadingScreen
import com.debanshu777.caraml.ui.screens.chat.ModelSelectorTopBar
import com.debanshu777.caraml.ui.screens.chat.NoModelsScreen
import com.debanshu777.caraml.ui.viewmodel.ChatUiState
import com.debanshu777.caraml.ui.viewmodel.ChatViewModel

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onNavigateToSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    val topModels by viewModel.topModels.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val uiState by viewModel.uiState.collectAsState()

    ChatScreenContent(
        uiState = uiState,
        topModels = topModels,
        selectedModel = selectedModel,
        onSelectModel = viewModel::selectModel,
        onSendMessage = viewModel::sendMessage,
        onCancelGeneration = viewModel::cancelGeneration,
        onNavigateToSearch = onNavigateToSearch,
        modifier = modifier
    )
}

@Composable
fun ChatScreenContent(
    uiState: ChatUiState,
    topModels: List<LocalModelEntity>,
    selectedModel: LocalModelEntity?,
    onSelectModel: (LocalModelEntity) -> Unit,
    onSendMessage: (String) -> Unit,
    onCancelGeneration: () -> Unit,
    onNavigateToSearch: () -> Unit,
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
        ModelSelectorTopBar(
            selectedModel = selectedModel,
            topModels = topModels,
            onSelectModel = onSelectModel,
            onDownloadModelClick = onNavigateToSearch
        )

        when (val state = uiState) {
            is ChatUiState.NoModels -> {
                NoModelsScreen(onDownloadModelClick = onNavigateToSearch)
            }

            is ChatUiState.ModelLoading -> {
                ModelLoadingScreen()
            }

            is ChatUiState.ModelError -> {
                ModelErrorScreen(
                    errorMessage = state.message,
                    onTryAnotherModelClick = onNavigateToSearch
                )
            }

            is ChatUiState.Ready -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    ChatMessageList(
                        messages = state.messages,
                        listState = listState,
                        modifier = Modifier.weight(1f)
                    )

                    ChatInputBar(
                        isGenerating = state.isGenerating,
                        onSendMessage = onSendMessage,
                        onCancelGeneration = onCancelGeneration
                    )
                }
            }
        }
    }
}
