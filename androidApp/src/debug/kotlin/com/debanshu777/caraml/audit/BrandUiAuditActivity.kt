package com.debanshu777.caraml.audit

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.debanshu777.caraml.core.drawer.AppDrawerShell
import com.debanshu777.caraml.core.navigation.AppScreen
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.core.theme.CaraMLTheme
import com.debanshu777.caraml.core.theme.ThemeMode
import com.debanshu777.caraml.core.theme.ThemePreferences
import com.debanshu777.caraml.core.ui.components.AuroraBackdrop
import com.debanshu777.caraml.features.chat.data.ChatMessage
import com.debanshu777.caraml.features.chat.data.MessageDelivery
import com.debanshu777.caraml.features.chat.data.MessageRole
import com.debanshu777.caraml.features.chat.domain.GenerationMode
import com.debanshu777.caraml.features.chat.presentation.ChatScreenContent
import com.debanshu777.caraml.features.chat.presentation.ChatUiState
import com.debanshu777.caraml.features.chat.presentation.StreamingState
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList

/** Shell-only debug UI fixtures. No inference, repositories, persistence, media, or network access. */
class BrandUiAuditActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val options = runCatching { AuditOptions.from(intent) }.getOrNull()
        setContent {
            CaraMLTheme(
                preferences = ThemePreferences(
                    themeMode = options?.theme ?: ThemeMode.LIGHT,
                    reduceMotion = options?.reducedMotion ?: true,
                    softEffects = options?.softEffects ?: true,
                ),
                onEffectiveDarkThemeChanged = { dark ->
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                },
            ) {
                if (options == null) {
                    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp)) {
                        Text("Invalid debug UI state options", color = AppTheme.colors.onSurface)
                    }
                } else {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, options.fontScale)) {
                        AuditScreen(options)
                    }
                }
            }
        }
    }
}

private enum class AuditState(val argument: String) {
    Empty("empty"), Loading("loading"), Missing("missing"), LoadError("load-error"),
    Thinking("thinking"), Writing("writing"), Completed("completed"), Stopped("stopped"),
    GenerationError("generation-error"), ImageProgress("image-progress"),
    NoImageModel("no-image-model"), NoVideoModel("no-video-model"),
}

private data class AuditOptions(
    val state: AuditState,
    val theme: ThemeMode,
    val reducedMotion: Boolean,
    val softEffects: Boolean,
    val fontScale: Float,
) {
    companion object {
        @Suppress("DEPRECATION") // Read only an exact allowlist of primitive Bundle values.
        fun from(intent: Intent): AuditOptions {
            require(intent.data == null && intent.clipData == null && intent.selector == null)
            val extras = intent.extras ?: Bundle()
            require(extras.keySet().all { it in setOf("state", "theme", "reducedMotion", "softEffects", "fontScale") })
            fun string(key: String, default: String) = if (!extras.containsKey(key)) default else
                (extras.get(key) as? String)?.takeIf { it.length <= 32 } ?: error("Invalid audit options")
            fun boolean(key: String, default: Boolean) = if (!extras.containsKey(key)) default else
                extras.get(key) as? Boolean ?: error("Invalid audit options")
            val state = AuditState.entries.singleOrNull { it.argument == string("state", "empty") }
                ?: error("Invalid audit options")
            val theme = when (string("theme", "light")) {
                "light" -> ThemeMode.LIGHT
                "dark" -> ThemeMode.DARK
                else -> error("Invalid audit options")
            }
            val scale = if (!extras.containsKey("fontScale")) 1f else
                extras.get("fontScale") as? Float ?: error("Invalid audit options")
            require(scale.isFinite() && scale in 1f..2f)
            return AuditOptions(state, theme, boolean("reducedMotion", true), boolean("softEffects", true), scale)
        }
    }
}

@Composable
private fun AuditScreen(options: AuditOptions) {
    val initial = remember(options.state) { fixture(options.state) }
    var uiState by remember { mutableStateOf(initial.first) }
    var streaming by remember { mutableStateOf(initial.second) }
    var mode by remember { mutableStateOf(when (options.state) {
        AuditState.ImageProgress, AuditState.NoImageModel -> GenerationMode.Image
        AuditState.NoVideoModel -> GenerationMode.Video
        else -> GenerationMode.Text
    }) }
    var drawerModeInitialized by remember { mutableStateOf(false) }
    var nextId by remember { mutableIntStateOf(0) }
    val backStack = remember { NavBackStack<NavKey>(AppScreen.Home) }
    fun browse() {
        backStack.clear()
        backStack.add(AppScreen.Search)
    }
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime)) {
        AppDrawerShell(modifier = Modifier.fillMaxSize(), backStack = backStack) {
            val drawerModes = com.debanshu777.caraml.core.drawer.LocalGenerationModeController.current
            androidx.compose.runtime.LaunchedEffect(drawerModes.mode) {
                if (!drawerModeInitialized) {
                    drawerModeInitialized = true
                    drawerModes.setState(mode)
                } else if (drawerModes.mode != mode) {
                    mode = drawerModes.mode
                    streaming = StreamingState()
                    uiState = ready(mode)
                }
            }
            AuroraBackdrop(modifier = Modifier.fillMaxSize()) {
                if (backStack.lastOrNull() == AppScreen.Home) {
                    ChatScreenContent(
                        uiState = uiState,
                        streamingState = streaming,
                        onSelectModel = { model -> (uiState as? ChatUiState.Ready)?.let { uiState = it.copy(selectedModel = model) } },
                        onSendMessage = { draft ->
                            val current = uiState as? ChatUiState.Ready
                            if (current != null && !current.isGenerating && draft.isNotBlank() && draft.length <= 8_000) {
                                val replyId = "debug-reply-${nextId++}"
                                uiState = current.copy(
                                    messages = (current.messages + ChatMessage(role = MessageRole.User, text = draft) +
                                        ChatMessage(id = replyId, role = MessageRole.Assistant, text = "")).toPersistentList(),
                                    isGenerating = true,
                                )
                                streaming = StreamingState(
                                    streamingMessageId = replyId,
                                    streamingThinkingText = "Debug UI preview. No model is running.",
                                )
                            }
                        },
                        onCancelGeneration = {
                            (uiState as? ChatUiState.Ready)?.let { current ->
                                uiState = current.copy(
                                    isGenerating = false,
                                    messages = current.messages.map { message ->
                                        if (message.id == streaming.streamingMessageId) message.copy(
                                            text = streaming.streamingText.ifBlank { message.text },
                                            thinking = streaming.streamingThinkingText.takeIf { it.isNotBlank() } ?: message.thinking,
                                            delivery = MessageDelivery.Stopped,
                                        ) else message
                                    }.toPersistentList(),
                                )
                                streaming = StreamingState()
                            }
                        },
                        onRetryCurrentModel = { uiState = ChatUiState.ModelLoading },
                        onNavigateToSearch = ::browse,
                        onNavigateToModelDetail = { _, _ -> browse() },
                        controlledGenerationMode = mode,
                    )
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text("Debug UI state", style = AppTheme.typography.headingLarge, color = AppTheme.colors.onSurface)
                        Text("Open the main app for real models and settings. This screen only previews chat states.", color = AppTheme.colors.onSurface)
                        Button(onClick = { backStack.clear(); backStack.add(AppScreen.Home) }) { Text("Return to Create") }
                    }
                }
            }
        }
        Text(
            text = "Debug UI state · ${options.state.argument}",
            modifier = Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(top = 4.dp, end = 8.dp)
                .background(AppTheme.colors.surface.copy(alpha = .95f), AppTheme.shapes.small)
                .padding(horizontal = 6.dp, vertical = 2.dp),
            style = AppTheme.typography.labelSmall,
            color = AppTheme.colors.onSurfaceVariant,
        )
    }
}

private fun fixture(state: AuditState): Pair<ChatUiState, StreamingState> {
    when (state) {
        AuditState.Loading -> return ChatUiState.ModelLoading to StreamingState()
        AuditState.Missing -> return ChatUiState.MissingComponents(
            missingComponentLabels = listOf("Text encoder", "Variational autoencoder"),
            modelName = "Moonlight image model", modelId = "debug/moonlight",
        ) to StreamingState()
        AuditState.LoadError -> return ChatUiState.ModelError(
            "The selected local model could not be opened. This is a debug preview.", canRetryCurrentModel = true,
        ) to StreamingState()
        AuditState.NoImageModel -> return ChatUiState.NoModelsForMode(GenerationMode.Image) to StreamingState()
        AuditState.NoVideoModel -> return ChatUiState.NoModelsForMode(GenerationMode.Video) to StreamingState()
        AuditState.Empty -> return ready(GenerationMode.Text) to StreamingState()
        else -> Unit
    }
    val mode = if (state == AuditState.ImageProgress) GenerationMode.Image else GenerationMode.Text
    val output = when (state) {
        AuditState.Completed -> "On the quiet side of the moon, a tiny noodle shop switched on its lantern. Its first customer was a comet, asking for something to go."
        AuditState.Stopped -> "On the quiet side of the moon, a tiny noodle shop switched on its lantern."
        AuditState.GenerationError -> "The little astronaut had just opened the door when"
        else -> ""
    }
    val delivery = when (state) {
        AuditState.Completed -> MessageDelivery.Complete
        AuditState.Stopped -> MessageDelivery.Stopped
        AuditState.GenerationError -> MessageDelivery.Error
        else -> null
    }
    val active = state in setOf(AuditState.Thinking, AuditState.Writing, AuditState.ImageProgress)
    val messages = persistentListOf(
        ChatMessage(id = "debug-user", role = MessageRole.User, text = if (mode == GenerationMode.Image)
            "A tiny noodle shop on the moon, in clay." else "Write a tiny story about a noodle shop on the moon."),
        ChatMessage(id = "debug-reply", role = MessageRole.Assistant, text = output, delivery = delivery),
    )
    val stream = if (!active) StreamingState() else StreamingState(
        streamingMessageId = "debug-reply",
        streamingText = if (state == AuditState.Writing) "On the quiet side of the moon, a tiny noodle shop switched on its lantern. Its first customer" else "",
        streamingThinkingText = if (state == AuditState.Thinking) "A quiet moon, a tiny kitchen, and an unexpected first customer…" else "",
        pendingMediaGeneration = state == AuditState.ImageProgress,
        imageGenStep = if (state == AuditState.ImageProgress) 12 else 0,
        imageGenTotalSteps = if (state == AuditState.ImageProgress) 30 else 0,
        imageGenRequestedSteps = if (state == AuditState.ImageProgress) 30 else 0,
        imageGenElapsedSeconds = if (state == AuditState.ImageProgress) 8 else 0,
    )
    return ready(mode).copy(messages = messages, isGenerating = active) to stream
}

private fun ready(mode: GenerationMode): ChatUiState.Ready {
    val model = LocalModelEntity(
        id = 1, modelId = if (mode == GenerationMode.Text) "Qwen/Qwen3-4B" else "debug/Moonlight",
        filename = "debug-ui-only.gguf", localPath = "", sizeBytes = null, downloadedAt = 0,
        author = "Debug fixture", libraryName = null,
        pipelineTag = when (mode) {
            GenerationMode.Text -> "text-generation"
            GenerationMode.Image -> "text-to-image"
            GenerationMode.Video -> "text-to-video"
        },
    )
    return ChatUiState.Ready(generationMode = mode, selectedModel = model, topModels = persistentListOf(model))
}
