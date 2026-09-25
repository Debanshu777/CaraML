package com.debanshu777.caraml.features.chat.presentation

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.debanshu777.caraml.core.drawer.DrawerController
import com.debanshu777.caraml.core.drawer.GenerationModeController
import com.debanshu777.caraml.core.drawer.LocalDrawerController
import com.debanshu777.caraml.core.drawer.LocalFocusModeController
import com.debanshu777.caraml.core.drawer.LocalGenerationModeController
import com.debanshu777.caraml.core.drawer.LocalNavigationMenuAction
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.core.theme.LocalSpacing
import com.debanshu777.caraml.core.theme.AuroraSurfaceLevel
import com.debanshu777.caraml.core.theme.auroraColors
import com.debanshu777.caraml.core.theme.prismShapes
import com.debanshu777.caraml.core.ui.components.CaraMLPane
import com.debanshu777.caraml.core.ui.components.AuroraFocalSurface
import com.debanshu777.caraml.core.ui.components.CommandSurface
import com.debanshu777.caraml.core.ui.components.FocalEntrance
import com.debanshu777.caraml.core.ui.layout.AppContentKind
import com.debanshu777.caraml.core.ui.layout.AppNavigationLayout
import com.debanshu777.caraml.core.ui.layout.LocalAppNavigationLayout
import com.debanshu777.caraml.core.ui.layout.ResponsiveContentPane
import com.debanshu777.caraml.core.ui.motion.LocalAuroraMotionPolicy
import com.debanshu777.caraml.features.chat.domain.GenerationMode
import com.debanshu777.caraml.features.chat.presentation.components.providers.ChatMessageListPreviewProvider
import com.debanshu777.caraml.features.chat.presentation.components.providers.LiveGenerationStatsPreviewProvider
import com.debanshu777.caraml.features.chat.presentation.components.providers.LocalModelPreviewProvider
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import com.debanshu777.caraml.features.chat.presentation.components.ChatInputBar
import com.debanshu777.caraml.features.chat.presentation.components.ContextStatsIndicator
import com.debanshu777.caraml.features.chat.presentation.components.ChatMessageList
import com.debanshu777.caraml.features.chat.presentation.components.GenerationStatsBar
import com.debanshu777.caraml.features.chat.presentation.components.ModelErrorScreen
import com.debanshu777.caraml.features.chat.presentation.components.ModelLoadingScreen
import com.debanshu777.caraml.features.chat.presentation.components.ModelSelectorTopBar
import com.debanshu777.caraml.features.modelhub.presentation.search.ModelHubBrowseMode

internal val LocalCreateSafeDrawingInsetsOverride =
    staticCompositionLocalOf<WindowInsets?> { null }

@Immutable
data class ChatEmptyStateCopy(
    val title: String,
    val supportingText: String,
)

internal fun emptyStateCopy(mode: GenerationMode): ChatEmptyStateCopy = when (mode) {
    GenerationMode.Text -> ChatEmptyStateCopy(
        title = "Start with a private thought.",
        supportingText = "Ask a question, shape an idea, or begin writing. Nothing leaves this device.",
    )
    GenerationMode.Image -> ChatEmptyStateCopy(
        title = "Create without the cloud.",
        supportingText = "Describe a scene and generate it entirely on this device.",
    )
    GenerationMode.Video -> ChatEmptyStateCopy(
        title = "Set ideas in motion.",
        supportingText = "Describe a short sequence for local video generation.",
    )
}

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
        onSelectModel = viewModel::selectModel,
        onSendMessage = viewModel::sendMessage,
        onCancelGeneration = viewModel::cancelGeneration,
        onConfirmLoad = viewModel::confirmPendingLoad,
        onAcceptAlternative = viewModel::acceptSaferPlan,
        onRetryLoad = viewModel::retryPendingLoad,
        onRetryCurrentModel = viewModel::retryCurrentModel,
        onCancelLoad = viewModel::cancelPendingLoad,
        loadMedia = viewModel::loadGeneratedMedia,
        onNavigateToSearch = onNavigateToSearch,
        onNavigateToModelDetail = onNavigateToModelDetail,
        contextIndicator = {
            ContextStatsIndicator(liveStats = streamingState.liveStats)
        },
        modifier = modifier,
        onGenerationModeSelected = modeController::setState,
        controlledGenerationMode = modeController.mode,
    )
}

@Composable
fun ChatScreenContent(
    uiState: ChatUiState,
    streamingState: StreamingState,
    onSelectModel: (LocalModelEntity) -> Unit,
    onSendMessage: (String) -> Unit,
    onCancelGeneration: () -> Unit,
    onConfirmLoad: (PendingLoadAction.ConfirmRisk) -> Unit = {},
    onAcceptAlternative: (PendingLoadAction) -> Unit = {},
    onRetryLoad: (PendingLoadAction.RetryQuarantined) -> Unit = {},
    onRetryCurrentModel: () -> Unit = {},
    onCancelLoad: (PendingLoadAction) -> Unit = {},
    loadMedia: suspend (String) -> ByteArray? = { null },
    onNavigateToSearch: () -> Unit,
    onNavigateToModelDetail: (modelId: String, mode: ModelHubBrowseMode) -> Unit = { _, _ -> },
    contextIndicator: @Composable RowScope.() -> Unit = {},
    modifier: Modifier = Modifier,
    onGenerationModeSelected: (GenerationMode) -> Unit = {},
    controlledGenerationMode: GenerationMode? = null,
) {
    val listState = rememberLazyListState()
    val navigationLayout = LocalAppNavigationLayout.current
    val navigationMenuAction = LocalNavigationMenuAction.current
    val focusModeController = LocalFocusModeController.current
    val safeDrawingInsets = LocalCreateSafeDrawingInsetsOverride.current ?: WindowInsets.safeDrawing
    val generationMode = controlledGenerationMode ?: when (val state = uiState) {
        is ChatUiState.Ready -> state.generationMode
        is ChatUiState.NoModelsForMode -> state.mode
        else -> GenerationMode.Text
    }

    val messageCount = (uiState as? ChatUiState.Ready)?.messages?.size ?: 0
    val focusModeEnabled = messageCount > 0 && focusModeController != null
    val composerInsets = safeDrawingInsets.only(
        WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
    )
    val scaffoldContentInsets = if (focusModeEnabled) {
        safeDrawingInsets
    } else {
        composerInsets
    }
    val imeBottomPadding = WindowInsets.ime.asPaddingValues().calculateBottomPadding()

    SideEffect {
        focusModeController?.update(focusModeEnabled)
    }
    DisposableEffect(focusModeController) {
        onDispose { focusModeController?.update(false) }
    }

    CreateRouteCanvas(
        focal = when (uiState) {
            is ChatUiState.Ready -> uiState.messages.isEmpty()
            ChatUiState.NoModels, is ChatUiState.NoModelsForMode -> true
            else -> false
        },
        modifier = modifier,
    ) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        contentWindowInsets = scaffoldContentInsets,
        topBar = {
            if (!focusModeEnabled) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(
                            safeDrawingInsets.only(
                                WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                            ),
                        ),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    ModelSelectorTopBar(
                        title = "Create",
                        onMenuClick = navigationMenuAction,
                        modifier = Modifier
                            .widthIn(max = 840.dp)
                            .fillMaxWidth()
                            .padding(
                                horizontal = if (
                                    navigationLayout == AppNavigationLayout.ModalSidebar
                                ) {
                                    16.dp
                                } else {
                                    24.dp
                                },
                            ),
                        generationMode = generationMode.takeUnless {
                            navigationLayout == AppNavigationLayout.Sidebar
                        },
                        onGenerationModeSelected = onGenerationModeSelected.takeIf {
                            navigationLayout != AppNavigationLayout.Sidebar
                        },
                    )
                }
            }
        },
        bottomBar = {
            if (uiState is ChatUiState.Ready) {
                ResponsiveContentPane(
                    kind = AppContentKind.Chat,
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(composerInsets),
                    fillMaxHeight = false,
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
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
                            contextIndicator = contextIndicator,
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        val layoutDirection = LocalLayoutDirection.current
        val bottomPadding = paddingValues.calculateBottomPadding()
        LaunchedEffect(messageCount, imeBottomPadding, bottomPadding) {
            if (messageCount > 0) {
                listState.animateScrollToItem(messageCount - 1)
            }
        }
        ResponsiveContentPane(
            kind = AppContentKind.Chat,
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = paddingValues.calculateStartPadding(layoutDirection),
                    top = paddingValues.calculateTopPadding(),
                    end = paddingValues.calculateEndPadding(layoutDirection),
                    bottom = if (uiState is ChatUiState.Ready) 0.dp else bottomPadding,
                ),
        ) {
            when (uiState) {
                is ChatUiState.NoModels -> {
                    CreateStateViewport {
                        CreateUnavailableWorkspace(
                            mode = generationMode,
                            supportingText = "Choose a local model to begin. Your prompts and responses stay on this device.",
                            onBrowseModels = onNavigateToSearch,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                is ChatUiState.NoModelsForMode -> {
                    CreateStateViewport {
                        CreateUnavailableWorkspace(
                            mode = uiState.mode,
                            supportingText = when (uiState.mode) {
                                GenerationMode.Text -> "Choose a local language model to begin."
                                GenerationMode.Image -> "Choose a local image model to begin."
                                GenerationMode.Video -> "Choose a local video model to begin."
                            },
                            onBrowseModels = onNavigateToSearch,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                is ChatUiState.ModelLoading -> {
                    CreateStateViewport {
                        ModelLoadingScreen(Modifier.fillMaxWidth())
                    }
                }

                is ChatUiState.ModelError -> {
                    CreateStateViewport {
                        ModelErrorScreen(
                            errorMessage = uiState.message,
                            onRetryCurrentModelClick = onRetryCurrentModel.takeIf {
                                uiState.canRetryCurrentModel
                            },
                            onTryAnotherModelClick = onNavigateToSearch,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                is ChatUiState.MissingComponents -> {
                    CreateStateViewport {
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
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                is ChatUiState.LoadActionRequired -> {
                    CreateStateViewport {
                        key(uiState.action) {
                            LoadActionRequiredScreen(
                                action = uiState.action,
                                onConfirmLoad = onConfirmLoad,
                                onAcceptAlternative = onAcceptAlternative,
                                onRetryLoad = onRetryLoad,
                                onCancelLoad = onCancelLoad,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                is ChatUiState.Ready -> {
                    Box(modifier = Modifier.fillMaxSize()) {
                        ChatMessageList(
                            messages = uiState.messages,
                            listState = listState,
                            streamingMessageId = streamingState.streamingMessageId,
                            streamingState = streamingState,
                            loadMedia = loadMedia,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                top = if (focusModeEnabled) 72.dp else 0.dp,
                                bottom = bottomPadding,
                            ),
                        )
                        if (uiState.messages.isEmpty()) {
                            AnimatedCreateEmptyState(
                                mode = uiState.generationMode,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(bottom = bottomPadding),
                            )
                        }

                        if (focusModeEnabled && navigationMenuAction != null) {
                            IconButton(
                                onClick = navigationMenuAction,
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(LocalSpacing.current.s)
                                    .size(48.dp)
                                    .testTag("focus-navigation-action"),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Menu,
                                    contentDescription = "Open navigation menu",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun CreateRouteCanvas(
    focal: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    if (focal) {
        AuroraFocalSurface(
            modifier = modifier.testTag("create-focal-canvas"),
            shape = RectangleShape,
            entrance = FocalEntrance.OneShot,
            content = content,
        )
    } else {
        Box(modifier = modifier, content = content)
    }
}

@Composable
private fun CreateStateViewport(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = LocalSpacing.current.l),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(
            space = LocalSpacing.current.m,
            alignment = Alignment.CenterVertically,
        ),
    ) {
        item {
            Box(modifier = Modifier.fillParentMaxWidth()) {
                content()
            }
        }
    }
}

@Composable
private fun AnimatedCreateEmptyState(
    mode: GenerationMode,
    modifier: Modifier = Modifier,
) {
    val motion = LocalAuroraMotionPolicy.current
    val focalAccent = MaterialTheme.auroraColors.focusPrimary.copy(alpha = 1f)
    Crossfade(
        targetState = mode,
        modifier = modifier,
        animationSpec = tween(
            durationMillis = if (motion.spatialTransitionsEnabled) {
                180
            } else {
                minOf(90, motion.opacityDurationMillis)
            },
        ),
        label = "create mode statement",
    ) { targetMode ->
        val copy = emptyStateCopy(targetMode)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 360.dp)
                .testTag("create-empty-state")
                .padding(horizontal = LocalSpacing.current.l, vertical = LocalSpacing.current.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.s),
        ) {
            WorkspaceGlyph()
            Text(
                text = "${targetMode.name.uppercase()} WORKSPACE",
                style = MaterialTheme.typography.labelSmall,
                color = focalAccent,
                fontWeight = FontWeight.Bold,
                letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified,
                textAlign = TextAlign.Center,
            )
            Text(
                modifier = Modifier.heightIn(min = 96.dp),
                text = copy.title,
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center,
            )
            Text(
                modifier = Modifier.heightIn(min = 72.dp),
                text = copy.supportingText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun CreateUnavailableWorkspace(
    mode: GenerationMode,
    supportingText: String,
    onBrowseModels: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val copy = emptyStateCopy(mode)
    val focalAccent = MaterialTheme.auroraColors.focusPrimary.copy(alpha = 1f)
    Column(
        modifier = modifier
            .testTag("create-empty-state")
            .padding(horizontal = LocalSpacing.current.l, vertical = LocalSpacing.current.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.m),
    ) {
        WorkspaceGlyph()
        Text(
            text = "${mode.name.uppercase()} WORKSPACE",
            style = MaterialTheme.typography.labelSmall,
            color = focalAccent,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            modifier = Modifier.heightIn(min = 96.dp),
            text = copy.title,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
        )
        Text(
            modifier = Modifier.heightIn(min = 72.dp),
            text = supportingText,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        CommandSurface(
            focused = false,
            active = false,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onBrowseModels)
                .semantics(mergeDescendants = true) {
                    contentDescription = "Browse models"
                },
            contentPadding = PaddingValues(horizontal = LocalSpacing.current.l),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Browse models",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Default.ArrowForward,
                    contentDescription = null,
                    tint = focalAccent,
                )
            }
        }
    }
}

@Composable
private fun WorkspaceGlyph() {
    val focalAccent = MaterialTheme.auroraColors.focusPrimary.copy(alpha = 1f)
    Surface(
        modifier = Modifier
            .size(52.dp)
            .clearAndSetSemantics { },
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.78f),
        contentColor = focalAccent,
        shape = MaterialTheme.prismShapes.focal,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = "I",
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = FontFamily.Monospace,
                color = focalAccent,
            )
        }
    }
}

@Composable
private fun LoadActionRequiredScreen(
    action: PendingLoadAction,
    onConfirmLoad: (PendingLoadAction.ConfirmRisk) -> Unit,
    onAcceptAlternative: (PendingLoadAction) -> Unit,
    onRetryLoad: (PendingLoadAction.RetryQuarantined) -> Unit,
    onCancelLoad: (PendingLoadAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val (title, detail) = when (action) {
        is PendingLoadAction.ConfirmRisk -> "Confirm model load" to
            "This configuration may put the device under heavy memory pressure."
        is PendingLoadAction.AcceptAlternative -> "Safer configuration available" to
            "A lower-resource configuration is available for this device."
        is PendingLoadAction.AcceptSafeAlternative -> "Safer configuration available" to
            "A lower-resource configuration is available for this device."
        is PendingLoadAction.RetryQuarantined -> "Previous load may have crashed" to
            "This exact configuration is paused. Retry it only if you accept the risk."
    }
    val compromises = when (action) {
        is PendingLoadAction.ConfirmRisk -> action.request.plan.compromises
        is PendingLoadAction.AcceptAlternative -> action.saferPlan.compromises
        is PendingLoadAction.AcceptSafeAlternative -> action.saferRequest.plan.compromises
        is PendingLoadAction.RetryQuarantined -> action.request.plan.compromises
    }
    Column(
        modifier = modifier.fillMaxWidth().padding(LocalSpacing.current.xl),
    ) {
        CaraMLPane(
            modifier = Modifier.fillMaxWidth(),
            level = AuroraSurfaceLevel.Pane,
        ) {
            Column(modifier = Modifier.padding(LocalSpacing.current.l)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(LocalSpacing.current.s))
                Text(detail, style = MaterialTheme.typography.bodyMedium)
                if (compromises.isNotEmpty()) {
                    Spacer(Modifier.height(LocalSpacing.current.m))
                    Text(
                        compromises.joinToString(separator = "\n") {
                            "• ${it.toString().lowercase().replace('_', ' ')}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(LocalSpacing.current.l))
                Button(
                    onClick = {
                        when (action) {
                            is PendingLoadAction.ConfirmRisk -> onConfirmLoad(action)
                            is PendingLoadAction.AcceptAlternative -> onAcceptAlternative(action)
                            is PendingLoadAction.AcceptSafeAlternative -> onAcceptAlternative(action)
                            is PendingLoadAction.RetryQuarantined -> onRetryLoad(action)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        when (action) {
                            is PendingLoadAction.ConfirmRisk -> "Continue"
                            is PendingLoadAction.AcceptAlternative -> "Use safer plan"
                            is PendingLoadAction.AcceptSafeAlternative -> "Use safer plan"
                            is PendingLoadAction.RetryQuarantined -> "Retry explicitly"
                        },
                    )
                }
                OutlinedButton(
                    onClick = { onCancelLoad(action) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Cancel")
                }
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
            .fillMaxWidth()
            .padding(LocalSpacing.current.xl),
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

        CaraMLPane(
            modifier = Modifier.fillMaxWidth(),
            level = AuroraSurfaceLevel.Pane,
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
