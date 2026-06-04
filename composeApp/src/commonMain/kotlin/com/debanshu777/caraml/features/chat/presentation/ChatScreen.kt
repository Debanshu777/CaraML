package com.debanshu777.caraml.features.chat.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.debanshu777.caraml.core.drawer.DrawerController
import com.debanshu777.caraml.core.drawer.GenerationModeController
import com.debanshu777.caraml.core.drawer.LocalDrawerController
import com.debanshu777.caraml.core.drawer.LocalGenerationModeController
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.core.theme.LocalSpacing
import com.debanshu777.caraml.features.chat.domain.GenerationMode
import com.debanshu777.caraml.features.chat.presentation.components.providers.ChatMessageListPreviewProvider
import com.debanshu777.caraml.features.chat.presentation.components.providers.LiveGenerationStatsPreviewProvider
import com.debanshu777.caraml.features.chat.presentation.components.providers.LocalModelPreviewProvider
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.StateFlow
import com.debanshu777.caraml.features.chat.presentation.components.ChatInputBar
import com.debanshu777.caraml.features.chat.presentation.components.ContextStatsIndicator
import com.debanshu777.caraml.features.chat.presentation.components.ChatMessageList
import com.debanshu777.caraml.features.chat.presentation.components.GenerationStatsBar
import com.debanshu777.caraml.features.chat.presentation.components.ModelErrorScreen
import com.debanshu777.caraml.features.chat.presentation.components.ModelLoadingScreen
import com.debanshu777.caraml.features.chat.presentation.components.ModelSelectorTopBar
import com.debanshu777.caraml.features.chat.presentation.components.NoCompatibleModelsScreen
import com.debanshu777.caraml.features.modelhub.presentation.search.ModelHubBrowseMode
import com.debanshu777.caraml.features.chat.presentation.components.NoModelsScreen

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onNavigateToSearch: () -> Unit,
    onNavigateToModelDetail: (modelId: String, mode: ModelHubBrowseMode) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val streamingState by viewModel.streamingState.collectAsStateWithLifecycle()
    val modeController = LocalGenerationModeController.current

    LaunchedEffect(modeController.mode) {
        viewModel.setGenerationMode(modeController.mode)
    }

    val vmMode = (uiState as? ChatUiState.Ready)?.generationMode
    LaunchedEffect(vmMode) {
        if (vmMode != null && modeController.mode != vmMode) {
            modeController.setState(vmMode)
        }
    }

    ChatScreenContent(
        uiState = uiState,
        streamingState = streamingState,
        streamingStateFlow = viewModel.streamingState,
        onSelectModel = viewModel::selectModel,
        onSendMessage = viewModel::sendMessage,
        onCancelGeneration = viewModel::cancelGeneration,
        onNavigateToSearch = onNavigateToSearch,
        onNavigateToModelDetail = onNavigateToModelDetail,
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
    onNavigateToModelDetail: (modelId: String, mode: ModelHubBrowseMode) -> Unit = { _, _ -> },
    contextIndicator: @Composable RowScope.() -> Unit = {},
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val drawerController = LocalDrawerController.current

    val topBarTitle = when (val s = uiState) {
        is ChatUiState.Ready -> when (s.generationMode) {
            GenerationMode.Text -> "Chat"
            GenerationMode.Image -> "Image"
            GenerationMode.Video -> "Video"
        }
        is ChatUiState.NoModelsForMode -> when (s.mode) {
            GenerationMode.Text -> "Chat"
            GenerationMode.Image -> "Image"
            GenerationMode.Video -> "Video"
        }
        else -> "Assistant"
    }

    val messageCount = (uiState as? ChatUiState.Ready)?.messages?.size ?: 0
    LaunchedEffect(messageCount) {
        if (messageCount > 0) {
            listState.animateScrollToItem(messageCount - 1)
        }
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            ModelSelectorTopBar(
                title = topBarTitle,
                onMenuClick = { drawerController.toggle() }
            )
        },
        bottomBar = {
            if (uiState is ChatUiState.Ready) {
                Column {
                    if (uiState.generationMode == GenerationMode.Text &&
                        uiState.isGenerating &&
                        streamingState.liveStats != null
                    ) {
                        GenerationStatsBar(stats = streamingState.liveStats)
                    }

                    ChatInputBar(
                        generationMode = uiState.generationMode,
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

            is ChatUiState.NoModelsForMode -> {
                NoCompatibleModelsScreen(
                    mode = uiState.mode,
                    onDownloadModelClick = onNavigateToSearch,
                    modifier = Modifier.padding(paddingValues)
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

            is ChatUiState.MissingComponents -> {
                MissingComponentsScreen(
                    missingComponentLabels = uiState.missingComponentLabels,
                    modelName = uiState.modelName,
                    onGoToModelHubClick = onNavigateToSearch,
                    onFixComponentsClick = {
                        onNavigateToModelDetail(
                            uiState.modelId,
                            ModelHubBrowseMode.DiffusionImage,
                        )
                    },
                    modifier = Modifier.padding(paddingValues)
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

@Composable
private fun MissingComponentsScreen(
    missingComponentLabels: List<String>,
    modelName: String,
    onGoToModelHubClick: () -> Unit,
    onFixComponentsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(LocalSpacing.current.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.Error,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.error
        )
        
        Spacer(modifier = Modifier.height(LocalSpacing.current.l))

        Text(
            text = "Missing Required Components",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(LocalSpacing.current.s))

        Text(
            text = "$modelName requires additional components to run:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(LocalSpacing.current.l))

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(LocalSpacing.current.l),
                verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.s)
            ) {
                missingComponentLabels.forEach { label ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.s),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(LocalSpacing.current.xl))

        Button(
            onClick = onFixComponentsClick,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                Icons.Default.Download,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.size(LocalSpacing.current.s))
            Text("Download missing components")
        }
    }
}

@Preview(name = "NoModels")
@Composable
private fun ChatScreenContentNoModelsPreview() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            CompositionLocalProvider(
                LocalDrawerController provides remember { DrawerController() },
                LocalGenerationModeController provides remember { GenerationModeController() },
            ) {
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
}

@Preview(name = "ModelLoading")
@Composable
private fun ChatScreenContentModelLoadingPreview() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            CompositionLocalProvider(
                LocalDrawerController provides remember { DrawerController() },
                LocalGenerationModeController provides remember { GenerationModeController() },
            ) {
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
}

@Preview(name = "ModelError")
@Composable
private fun ChatScreenContentModelErrorPreview() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            CompositionLocalProvider(
                LocalDrawerController provides remember { DrawerController() },
                LocalGenerationModeController provides remember { GenerationModeController() },
            ) {
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
}

@Preview(name = "Ready", widthDp = 360, heightDp = 800)
@Composable
private fun ChatScreenContentReadyPreview() {
    val model = LocalModelPreviewProvider().values.elementAt(0)
    val messages = ChatMessageListPreviewProvider().values.elementAt(1)
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            CompositionLocalProvider(
                LocalDrawerController provides remember { DrawerController() },
                LocalGenerationModeController provides remember { GenerationModeController() },
            ) {
                ChatScreenContent(
                    uiState = ChatUiState.Ready(
                        messages = messages.toImmutableList(),
                        selectedModel = model,
                        topModels = persistentListOf(model),
                        generationMode = GenerationMode.Text,
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
}

@Preview(name = "Ready Generating", widthDp = 360, heightDp = 800)
@Composable
private fun ChatScreenContentReadyGeneratingPreview() {
    val model = LocalModelPreviewProvider().values.elementAt(0)
    val messages = ChatMessageListPreviewProvider().values.elementAt(1)
    val liveStats = LiveGenerationStatsPreviewProvider().values.elementAt(0)
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            CompositionLocalProvider(
                LocalDrawerController provides remember { DrawerController() },
                LocalGenerationModeController provides remember { GenerationModeController() },
            ) {
                ChatScreenContent(
                    uiState = ChatUiState.Ready(
                        messages = messages.toImmutableList(),
                        selectedModel = model,
                        topModels = persistentListOf(model),
                        generationMode = GenerationMode.Text,
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
}
