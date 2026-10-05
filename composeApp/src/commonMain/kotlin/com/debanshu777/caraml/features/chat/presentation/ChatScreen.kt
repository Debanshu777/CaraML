package com.debanshu777.caraml.features.chat.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import com.debanshu777.caraml.core.ui.components.BrandNavigationButton
import com.debanshu777.caraml.core.ui.components.BrandPalAppearance
import com.debanshu777.caraml.core.ui.components.BrandPalState
import com.debanshu777.caraml.features.chat.data.MessageRole
import com.debanshu777.caraml.features.chat.data.MessageDelivery
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.unit.sp
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
import com.debanshu777.caraml.core.ui.components.BrandPal
import com.debanshu777.caraml.core.ui.components.CaraMLPane
import com.debanshu777.caraml.core.ui.components.CommandSurface
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
import com.debanshu777.caraml.features.chat.presentation.components.GenerationModeSwitcher
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

internal fun emptyStateCopy(mode: GenerationMode): ChatEmptyStateCopy = ChatEmptyStateCopy(
    title = "Got a\nweird idea?",
    supportingText = "Good. Let’s do something with it.",
)

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
    val emptyListState = rememberLazyListState()
    val motion = LocalAuroraMotionPolicy.current
    val navigationMenuAction = LocalNavigationMenuAction.current
    val focusModeController = LocalFocusModeController.current
    val safeDrawingInsets = LocalCreateSafeDrawingInsetsOverride.current ?: WindowInsets.safeDrawing
    val ready = uiState as? ChatUiState.Ready
    val generationMode = controlledGenerationMode ?: when (uiState) {
        is ChatUiState.Ready -> uiState.generationMode
        is ChatUiState.NoModelsForMode -> uiState.mode
        else -> GenerationMode.Text
    }
    val hasConversation = ready?.messages?.isNotEmpty() == true
    val messageCount = ready?.messages?.size ?: 0
    val composerInsets = safeDrawingInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
    val imeBottomPadding = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    var draft by rememberSaveable { mutableStateOf("") }
    val inputFocusRequester = remember { FocusRequester() }
    var inputAttached by remember { mutableStateOf(false) }
    var inputFocused by remember { mutableStateOf(false) }
    var emptyViewportHeight by remember { mutableIntStateOf(0) }
    var focusDraftRequest by remember { mutableIntStateOf(0) }
    var handledFocusRequest by remember { mutableIntStateOf(0) }
    // A request belongs to the current enabled composer, including its model and mode.
    val composerOwner = ready?.takeUnless { it.isGenerating }?.let {
        Triple(generationMode, it.selectedModel, hasConversation)
    }
    val currentComposerOwner by rememberUpdatedState(composerOwner)
    LaunchedEffect(focusDraftRequest, composerOwner) {
        if (focusDraftRequest > handledFocusRequest) {
            handledFocusRequest = focusDraftRequest
            if (composerOwner != null) {
                if (!hasConversation) {
                    if (motion.spatialTransitionsEnabled) emptyListState.animateScrollToItem(2)
                    else emptyListState.scrollToItem(2)
                    withFrameNanos { }
                }
                if (inputAttached && currentComposerOwner == composerOwner) inputFocusRequester.requestFocus()
            }
        }
    }
    // The keyboard changes the scroll range after focus is granted. Reveal the whole
    // composer again in that settled viewport, rather than only the TextField's cursor.
    LaunchedEffect(inputFocused, emptyViewportHeight, imeBottomPadding, composerOwner) {
        if (inputFocused && !hasConversation && composerOwner != null && emptyViewportHeight > 0) {
            withFrameNanos { }
            if (inputAttached && currentComposerOwner == composerOwner) emptyListState.scrollToItem(2)
        }
    }
    SideEffect { focusModeController?.update(hasConversation) }
    DisposableEffect(focusModeController) {
        onDispose { focusModeController?.update(false) }
    }

    Scaffold(
        modifier = modifier.fillMaxSize().testTag("create-route-canvas"),
        containerColor = Color.Transparent,
        contentWindowInsets = safeDrawingInsets,
        bottomBar = {
            if (ready != null && hasConversation) {
                ResponsiveContentPane(
                    kind = AppContentKind.Chat,
                    modifier = Modifier.fillMaxWidth().windowInsetsPadding(composerInsets),
                    fillMaxHeight = false,
                ) {
                    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                        GenerationModeSwitcher(
                            mode = generationMode,
                            onModeSelected = onGenerationModeSelected,
                            modifier = Modifier.padding(bottom = 8.dp),
                            compact = true,
                        )
                        if (streamingState.isCompacting) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp).testTag("chat-context-maintenance"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (motion.pulseEnabled) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text("Making room for your next reply…", style = AppTheme.typography.labelBase, color = AppTheme.colors.onSurfaceVariant)
                            }
                        }
                        if (ready.generationMode == GenerationMode.Text && ready.isGenerating && streamingState.liveStats != null) {
                            GenerationStatsBar(stats = streamingState.liveStats)
                        }
                        ChatInputBar(
                            generationMode = ready.generationMode,
                            isGenerating = ready.isGenerating,
                            selectedModel = ready.selectedModel,
                            topModels = ready.topModels,
                            onSelectModel = onSelectModel,
                            onDownloadModelClick = onNavigateToSearch,
                            onSendMessage = onSendMessage,
                            onCancelGeneration = onCancelGeneration,
                            contextIndicator = contextIndicator,
                            draftText = draft,
                            onDraftTextChange = { draft = it },
                            isConversation = true,
                            inputFocusRequester = inputFocusRequester,
                            onInputAttachmentChanged = { inputAttached = it },
                            onInputFocusChanged = { inputFocused = it },
                        )
                    }
                }
            }
        },
    ) { paddingValues ->
        val bottomPadding = paddingValues.calculateBottomPadding()
        LaunchedEffect(messageCount, imeBottomPadding, bottomPadding) {
            if (messageCount > 0) {
                if (motion.spatialTransitionsEnabled) listState.animateScrollToItem(messageCount - 1)
                else listState.scrollToItem(messageCount - 1)
                withFrameNanos { }
                val layout = listState.layoutInfo
                val lastRow = layout.visibleItemsInfo.firstOrNull { it.index == messageCount - 1 }
                if (lastRow != null) {
                    val clippedBottom = lastRow.offset + lastRow.size + layout.afterContentPadding - layout.viewportEndOffset
                    if (clippedBottom > 0) {
                        if (motion.spatialTransitionsEnabled) {
                            listState.animateScrollBy(clippedBottom.toFloat(), tween(motion.peerTransitionMillis))
                        } else {
                            listState.scrollBy(clippedBottom.toFloat())
                        }
                    }
                }
            }
        }
        ResponsiveContentPane(
            kind = AppContentKind.Chat,
            modifier = Modifier.fillMaxSize().padding(paddingValues),
        ) {
            Column(Modifier.fillMaxSize()) {
                if (hasConversation) {
                    ConversationHeading(
                        title = ready.messages.firstOrNull { it.role == MessageRole.User }?.text.orEmpty(),
                        modelName = ready.selectedModel?.modelId?.substringAfterLast("/"),
                        onMenuClick = navigationMenuAction,
                    )
                    val lastMessage = ready.messages.lastOrNull()
                    val recoverable = !ready.isGenerating && lastMessage?.role == MessageRole.Assistant &&
                        lastMessage.delivery in setOf(MessageDelivery.Stopped, MessageDelivery.Error)
                    ChatMessageList(
                        messages = ready.messages,
                        listState = listState,
                        streamingMessageId = streamingState.streamingMessageId,
                        streamingState = streamingState,
                        loadMedia = loadMedia,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 20.dp),
                        footer = if (recoverable) ({
                            ReplyRecoveryActions(
                                stopped = lastMessage.delivery == MessageDelivery.Stopped && ready.generationMode == GenerationMode.Text,
                                onContinue = { onSendMessage("Please continue your previous reply.") },
                                onRetry = {
                                    ready.messages.lastOrNull { it.role == MessageRole.User }?.text
                                        ?.takeIf(String::isNotBlank)?.let(onSendMessage)
                                },
                                onNewIdea = { draft = ""; focusDraftRequest++ },
                            )
                        }) else null,
                    )
                } else if (ready != null || uiState is ChatUiState.NoModels || uiState is ChatUiState.NoModelsForMode) {
                    LazyColumn(
                        state = emptyListState,
                        modifier = Modifier.fillMaxSize().onSizeChanged { emptyViewportHeight = it.height },
                        contentPadding = PaddingValues(top = 18.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        item {
                            CreateWelcome(generationMode, navigationMenuAction)
                        }
                        item {
                            GenerationModeSwitcher(generationMode, onGenerationModeSelected)
                        }
                        item {
                            if (ready != null) {
                                ChatInputBar(
                                    generationMode = generationMode,
                                    isGenerating = ready.isGenerating,
                                    selectedModel = ready.selectedModel,
                                    topModels = ready.topModels,
                                    onSelectModel = onSelectModel,
                                    onDownloadModelClick = onNavigateToSearch,
                                    onSendMessage = onSendMessage,
                                    onCancelGeneration = onCancelGeneration,
                                    contextIndicator = contextIndicator,
                                    draftText = draft,
                                    onDraftTextChange = { draft = it },
                                    inputFocusRequester = inputFocusRequester,
                                    onInputAttachmentChanged = { inputAttached = it },
                                    onInputFocusChanged = { inputFocused = it },
                                )
                            } else {
                                CreateModelPrompt(generationMode, onNavigateToSearch)
                            }
                        }
                        if (ready != null) item {
                            StarterIdeas(onChoose = { draft = it; focusDraftRequest++ })
                        }
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        navigationMenuAction?.let { BrandNavigationButton(it) }
                        GenerationModeSwitcher(generationMode, onGenerationModeSelected, Modifier.weight(1f), compact = true)
                    }
                    CreateStateViewport {
                        when (uiState) {
                            ChatUiState.ModelLoading -> ModelLoadingScreen(Modifier.fillMaxWidth())
                            is ChatUiState.ModelError -> ModelErrorScreen(
                                errorMessage = uiState.message,
                                onRetryCurrentModelClick = onRetryCurrentModel.takeIf { uiState.canRetryCurrentModel },
                                onTryAnotherModelClick = onNavigateToSearch,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            is ChatUiState.MissingComponents -> MissingComponentsScreen(
                                missingComponentLabels = uiState.missingComponentLabels,
                                modelName = uiState.modelName,
                                onGoToModelHubClick = onNavigateToSearch,
                                onFixComponentsClick = { onNavigateToModelDetail(uiState.modelId, ModelHubBrowseMode.DiffusionImage) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            is ChatUiState.LoadActionRequired -> key(uiState.action) {
                                LoadActionRequiredScreen(uiState.action, onConfirmLoad, onAcceptAlternative, onRetryLoad, onCancelLoad, Modifier.fillMaxWidth())
                            }
                            else -> Unit
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationHeading(title: String, modelName: String?, onMenuClick: (() -> Unit)?) {
    val words = remember(title) { title.split(' ', '\n', '\t').filter(String::isNotBlank) }
    val conversationTitle = words.take(4).joinToString(" ").let { if (words.size > 4) "$it…" else it }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth <= 328.dp
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            onMenuClick?.let { BrandNavigationButton(it, Modifier.testTag("focus-navigation-action")) }
            Column(Modifier.weight(1f)) {
                Text(
                    text = conversationTitle.ifBlank { "A little conversation" },
                    style = AppTheme.typography.modelTitle21.copy(
                        fontSize = if (compact) 19.sp else 21.sp,
                        lineHeight = if (compact) 20.9.sp else 23.1.sp,
                        letterSpacing = (-0.6).sp,
                    ),
                    color = AppTheme.colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "CaraML · ${modelName ?: "Select a model"}",
                    style = AppTheme.typography.labelSmall,
                    color = AppTheme.colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CreateWelcome(mode: GenerationMode, onMenuClick: (() -> Unit)?) {
    val copy = emptyStateCopy(mode)
    var booped by remember(mode) { mutableStateOf(false) }
    val motion = LocalAuroraMotionPolicy.current
    val boop by animateFloatAsState(
        targetValue = if (booped && motion.spatialTransitionsEnabled) 1f else 0f,
        animationSpec = AppTheme.motion.pressSpec(),
        label = "Pocket pal hello",
    )
    LaunchedEffect(booped) {
        if (booped) {
            delay(650)
            booped = false
        }
    }
    val appearance = when (mode) {
        GenerationMode.Text -> BrandPalAppearance.Write
        GenerationMode.Image -> BrandPalAppearance.Imagine
        GenerationMode.Video -> BrandPalAppearance.Animate
    }
    BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = 248.dp).testTag("create-empty-state")) {
        val compact = maxWidth < 330.dp
        onMenuClick?.let { BrandNavigationButton(it, Modifier.align(Alignment.TopStart)) }
        Column(Modifier.padding(top = 68.dp, end = if (compact) 72.dp else 92.dp, bottom = 12.dp)) {
            Text(
                text = buildAnnotatedString {
                    append(copy.title.substringBefore('\n'))
                    append("\n")
                    withStyle(SpanStyle(color = AppTheme.actionColor)) { append(copy.title.substringAfter('\n')) }
                },
                style = if (compact) AppTheme.typography.hero36Compact else AppTheme.typography.hero42,
                color = AppTheme.colors.onSurface,
            )
            Text(
                copy.supportingText,
                modifier = Modifier.padding(top = 17.dp).widthIn(max = 220.dp),
                style = AppTheme.typography.bodySmall,
                color = AppTheme.colors.onSurface,
            )
        }
        Column(
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 68.dp)
                .widthIn(min = if (compact) 64.dp else 85.dp)
                .clickable(role = Role.Button, onClickLabel = "Say hello to CaraML") { booped = true },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BrandPal(
                state = BrandPalState.Idle,
                appearance = appearance,
                modifier = Modifier.size(if (compact) 68.dp else 88.dp).graphicsLayer {
                    scaleX = 1f + boop * .06f
                    scaleY = 1f + boop * .06f
                    translationY = -8.dp.toPx() * boop
                },
            )
            Text(
                if (booped) "oh, hi!" else "psst. tap me.",
                style = AppTheme.typography.labelSmall,
                color = AppTheme.colors.onSurface,
            )
        }
    }
}

@Composable
private fun CreateModelPrompt(mode: GenerationMode, onBrowseModels: () -> Unit) {
    CommandSurface(focused = false, active = false, contentPadding = PaddingValues(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = when (mode) {
                    GenerationMode.Text -> "A tiny astronaut opens a noodle shop…"
                    GenerationMode.Image -> "A tiny noodle shop on the moon, in clay…"
                    GenerationMode.Video -> "A tiny astronaut flips a noodle in slow motion…"
                },
                style = AppTheme.typography.bodyLarge,
                color = AppTheme.colors.onSurfaceVariant,
            )
            Text(
                "Choose a local ${when (mode) { GenerationMode.Text -> "language"; GenerationMode.Image -> "image"; GenerationMode.Video -> "video" }} model to begin.",
                style = AppTheme.typography.bodySmall,
                color = AppTheme.colors.onSurfaceVariant,
            )
            Button(onClick = onBrowseModels, modifier = Modifier.heightIn(min = 44.dp)) {
                Text("Browse models")
                Spacer(Modifier.size(8.dp))
                Icon(Icons.AutoMirrored.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StarterIdeas(onChoose: (String) -> Unit) {
    var surpriseIndex by remember { mutableIntStateOf(0) }
    val surprises = listOf(
        "Invent a tiny holiday for people who love rainy days.",
        "Tell me about a dragon who is terrible at keeping secrets.",
        "Imagine a garden that only blooms under moonlight.",
    )
    Column(Modifier.fillMaxWidth().padding(top = 3.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Or try", style = AppTheme.typography.labelBase, color = AppTheme.colors.onSurface)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StarterChip("Space noodles") { onChoose("Write a story about a tiny astronaut who opens a noodle shop.") }
            StarterChip("Bad band names") { onChoose("Pitch three wonderfully bad names for a band.") }
            StarterChip("Surprise me") { onChoose(surprises[surpriseIndex++ % surprises.size]) }
        }
    }
}

@Composable
private fun StarterChip(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 44.dp),
        contentPadding = PaddingValues(horizontal = 13.dp, vertical = 9.dp),
        border = BorderStroke(1.dp, AppTheme.colors.outlineVariant),
        shape = RoundedCornerShape(24.dp),
    ) { Text(label, style = AppTheme.typography.labelBase, color = AppTheme.colors.onSurface) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReplyRecoveryActions(stopped: Boolean, onContinue: () -> Unit, onRetry: () -> Unit, onNewIdea: () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (stopped) StarterChip("Continue", onContinue)
        StarterChip("Try again", onRetry)
        OutlinedButton(
            onClick = onNewIdea,
            modifier = Modifier.heightIn(min = 44.dp).semantics { contentDescription = "New idea. Clear the draft and keep this conversation." },
            shape = RoundedCornerShape(12.dp),
        ) { Text("New idea") }
    }
}

@Composable
private fun CreateStateViewport(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { item { Box(Modifier.fillParentMaxWidth()) { content() } } }
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
        modifier = modifier.fillMaxWidth(),
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
            .fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
    ) {
        BrandPal(BrandPalState.Paused, Modifier.size(44.dp))
        
        Spacer(modifier = Modifier.height(AppTheme.spacing.spacing16))

        Text(
            text = "A few pieces are missing",
            style = AppTheme.typography.stateTitle26,
            color = AppTheme.colors.onSurface
        )

        Spacer(modifier = Modifier.height(AppTheme.spacing.spacing8))

        Text(
            text = "$modelName requires additional components to run:",
            style = AppTheme.typography.bodyBase,
            color = AppTheme.colors.onSurface
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
