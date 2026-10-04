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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.core.drawer.DrawerController
import com.debanshu777.caraml.core.drawer.GenerationModeController
import com.debanshu777.caraml.core.drawer.LocalDrawerController
import com.debanshu777.caraml.core.drawer.LocalFocusModeController
import com.debanshu777.caraml.core.drawer.LocalGenerationModeController
import com.debanshu777.caraml.core.drawer.LocalNavigationMenuAction
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.core.theme.AuroraSurfaceLevel
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
                            .widthIn(max = AppTheme.dimensions.size840)
                            .fillMaxWidth()
                            .padding(
                                horizontal = if (
                                    navigationLayout == AppNavigationLayout.ModalSidebar
                                ) {
                                    AppTheme.spacing.spacing16
                                } else {
                                    AppTheme.spacing.spacing24
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
                        if (streamingState.isCompacting) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(AppTheme.spacing.spacing12)
                                    .testTag("chat-context-maintenance"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing8),
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(AppTheme.spacing.spacing16), strokeWidth = AppTheme.spacing.spacing2)
                                Text(
                                    text = "Making room for your next reply…",
                                    style = AppTheme.typography.labelBase,
                                    color = AppTheme.colors.onSurfaceVariant,
                                )
                            }
                        }
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
                    bottom = if (uiState is ChatUiState.Ready) AppTheme.dimensions.size0 else bottomPadding,
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
                                top = if (focusModeEnabled) AppTheme.dimensions.size72 else AppTheme.dimensions.size0,
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
                                    .padding(AppTheme.spacing.spacing8)
                                    .size(AppTheme.spacing.spacing48)
                                    .testTag("focus-navigation-action"),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Menu,
                                    contentDescription = "Open navigation menu",
                                    tint = AppTheme.colors.onSurface,
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
        contentPadding = PaddingValues(vertical = AppTheme.spacing.spacing16),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(
            space = AppTheme.spacing.spacing12,
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
    val focalAccent = AppTheme.auroraColors.focusPrimary.copy(alpha = AppTheme.effects.opaque)
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
                .heightIn(min = AppTheme.dimensions.size360)
                .testTag("create-empty-state")
                .padding(horizontal = AppTheme.spacing.spacing16, vertical = AppTheme.spacing.spacing24),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing8),
        ) {
            WorkspaceGlyph()
            Text(
                text = "${targetMode.name.uppercase()} WORKSPACE",
                style = AppTheme.typography.label12,
                color = focalAccent,
                textAlign = TextAlign.Center,
            )
            Text(
                modifier = Modifier.heightIn(min = AppTheme.dimensions.size96),
                text = copy.title,
                style = AppTheme.typography.heading32,
                color = AppTheme.colors.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                modifier = Modifier.heightIn(min = AppTheme.dimensions.size72),
                text = copy.supportingText,
                style = AppTheme.typography.bodyBase,
                color = AppTheme.colors.onSurfaceVariant,
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
    val focalAccent = AppTheme.auroraColors.focusPrimary.copy(alpha = AppTheme.effects.opaque)
    Column(
        modifier = modifier
            .testTag("create-empty-state")
            .padding(horizontal = AppTheme.spacing.spacing16, vertical = AppTheme.spacing.spacing24),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing12),
    ) {
        WorkspaceGlyph()
        Text(
            text = "${mode.name.uppercase()} WORKSPACE",
            style = AppTheme.typography.label12,
            color = focalAccent,
            textAlign = TextAlign.Center,
        )
        Text(
            modifier = Modifier.heightIn(min = AppTheme.dimensions.size96),
            text = copy.title,
            style = AppTheme.typography.heading32,
            color = AppTheme.colors.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            modifier = Modifier.heightIn(min = AppTheme.dimensions.size72),
            text = supportingText,
            style = AppTheme.typography.bodyLarge,
            color = AppTheme.colors.onSurfaceVariant,
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
            contentPadding = PaddingValues(horizontal = AppTheme.spacing.spacing16),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = AppTheme.dimensions.size56),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Browse models",
                    style = AppTheme.typography.bodyLarge,
                    color = AppTheme.colors.onSurface,
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
    val focalAccent = AppTheme.auroraColors.focusPrimary.copy(alpha = AppTheme.effects.opaque)
    Surface(
        modifier = Modifier
            .size(AppTheme.dimensions.size52)
            .clearAndSetSemantics { },
        color = AppTheme.colors.surfaceContainer.copy(alpha = AppTheme.effects.workspaceGlyphSurface),
        contentColor = focalAccent,
        shape = AppTheme.shapes.large,
        border = androidx.compose.foundation.BorderStroke(
            AppTheme.dimensions.size1,
            AppTheme.colors.outlineVariant,
        ),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = "I",
                style = AppTheme.typography.headingLargeMono,
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
        modifier = modifier.fillMaxWidth().padding(AppTheme.spacing.spacing24),
    ) {
        CaraMLPane(
            modifier = Modifier.fillMaxWidth(),
            level = AuroraSurfaceLevel.Pane,
        ) {
            Column(modifier = Modifier.padding(AppTheme.spacing.spacing16)) {
                Text(title, style = AppTheme.typography.headingBase)
                Spacer(Modifier.height(AppTheme.spacing.spacing8))
                Text(detail, style = AppTheme.typography.bodyBase)
                if (compromises.isNotEmpty()) {
                    Spacer(Modifier.height(AppTheme.spacing.spacing12))
                    Text(
                        compromises.joinToString(separator = "\n") {
                            "• ${it.toString().lowercase().replace('_', ' ')}"
                        },
                        style = AppTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(AppTheme.spacing.spacing16))
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
            .padding(AppTheme.spacing.spacing24),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.Error,
            contentDescription = null,
            modifier = Modifier.size(AppTheme.spacing.spacing64),
            tint = AppTheme.colors.error
        )
        
        Spacer(modifier = Modifier.height(AppTheme.spacing.spacing16))

        Text(
            text = "Missing Required Components",
            style = AppTheme.typography.headingLarge,
            color = AppTheme.colors.onSurface
        )

        Spacer(modifier = Modifier.height(AppTheme.spacing.spacing8))

        Text(
            text = "$modelName requires additional components to run:",
            style = AppTheme.typography.bodyBase,
            color = AppTheme.colors.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(AppTheme.spacing.spacing16))

        CaraMLPane(
            modifier = Modifier.fillMaxWidth(),
            level = AuroraSurfaceLevel.Pane,
        ) {
            Column(
                modifier = Modifier.padding(AppTheme.spacing.spacing16),
                verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing8)
            ) {
                missingComponentLabels.forEach { label ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing8),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Text(
                            text = "•",
                            style = AppTheme.typography.bodyBase,
                            color = AppTheme.colors.error
                        )
                        Text(
                            text = label,
                            style = AppTheme.typography.bodyBase,
                            color = AppTheme.colors.onSurface
                        )
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(AppTheme.spacing.spacing24))

        Button(
            onClick = onFixComponentsClick,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                Icons.Default.Download,
                contentDescription = null,
                modifier = Modifier.size(AppTheme.dimensions.size18)
            )
            Spacer(modifier = Modifier.size(AppTheme.spacing.spacing8))
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
