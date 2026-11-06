package com.debanshu777.caraml.benchmark

import android.os.Bundle
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Trace
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.debanshu777.caraml.core.drawer.AppDrawerShell
import com.debanshu777.caraml.core.navigation.AppScreen
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.core.theme.CaraMLTheme
import com.debanshu777.caraml.core.theme.ThemeMode
import com.debanshu777.caraml.core.theme.ThemePreferences
import com.debanshu777.caraml.core.theme.systemBarIconAppearance
import com.debanshu777.caraml.features.chat.data.ChatMessage
import com.debanshu777.caraml.features.chat.data.MessageDelivery
import com.debanshu777.caraml.features.chat.data.MessageRole
import com.debanshu777.caraml.features.chat.presentation.ChatScreenContent
import com.debanshu777.caraml.features.chat.presentation.ChatUiState
import com.debanshu777.caraml.features.chat.presentation.StreamingState
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.delay

/** Fixture v3: production page backdrop/insets, shell and message list; no model load or network. */
class ChatScrollActivity : ComponentActivity() {
    private var streaming by mutableStateOf(false)
    private val streamControl = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            streaming = intent?.getBooleanExtra("running", false) == true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Exported only in this benchmark-only activity, for its separate controller process.
        ContextCompat.registerReceiver(this, streamControl,
            IntentFilter("com.debanshu777.caraml.benchmark.STREAM"), ContextCompat.RECEIVER_EXPORTED)
        val richText = intent.getBooleanExtra("richText", false)
        val softEffects = intent.getBooleanExtra("softEffects", true)
        val messageCount = intent.getIntExtra("messageCount", 200).also { require(it in 200..2_000 && it % 2 == 0) }
        val streamPeriodMs = intent.getLongExtra("streamPeriodMs", 100).also { require(it in 25..1_000) }
        setContent {
            CaraMLTheme(
                preferences = ThemePreferences(themeMode = ThemeMode.LIGHT, reduceMotion = false, softEffects = softEffects),
                onEffectiveDarkThemeChanged = ::syncSystemBarAppearance,
            ) {
                val backStack = remember { NavBackStack<NavKey>(AppScreen.Home) }
                val state = remember { chatFixture(richText, messageCount) }
                var streamedText by remember { mutableStateOf("") }
                LaunchedEffect(streaming) {
                    if (!streaming) return@LaunchedEffect
                    val prefix = "Answer ${messageCount / 2 - 1}: Streaming a local response.\n\n"
                    val chunks = ("The model produces words while you browse history. " +
                        "**Markdown** and `inline code` update during generation.\n\n").split(" ")
                    streamedText = prefix
                    var updates = 0
                    Trace.setCounter("CaraML.fixtureStreamUpdates", 0)
                    while (streaming) {
                        delay(streamPeriodMs)
                        streamedText += chunks[updates % chunks.size] + " "
                        updates++
                        Trace.setCounter("CaraML.fixtureStreamUpdates", updates.toLong())
                    }
                }
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime)
                    .semantics { testTagsAsResourceId = true }) {
                    AppDrawerShell(modifier = Modifier.fillMaxSize(), backStack = backStack) {
                        // The production page owns its backdrop under the frosted chrome.
                        ChatScreenContent(
                            uiState = if (streaming) state.copy(isGenerating = true) else state,
                            streamingState = if (streaming) StreamingState(
                                streamingMessageId = "benchmark-message-${messageCount - 1}",
                                streamingText = streamedText,
                            ) else StreamingState(),
                            onSelectModel = {},
                            onSendMessage = {},
                            onCancelGeneration = {},
                            onNavigateToSearch = {},
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        unregisterReceiver(streamControl)
        super.onDestroy()
    }

    private fun syncSystemBarAppearance(darkTheme: Boolean) {
        val appearance = systemBarIconAppearance(darkTheme)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = appearance.useDarkStatusBarIcons
            isAppearanceLightNavigationBars = appearance.useDarkNavigationBarIcons
        }
    }
}

private fun chatFixture(richText: Boolean = false, messageCount: Int = 200) = ChatUiState.Ready(
    selectedModel = LocalModelEntity(
        id = 1, modelId = "benchmark/local-model", filename = "fixture.gguf",
        localPath = "", sizeBytes = 1_000_000_000, downloadedAt = 0,
        author = "benchmark", libraryName = "gguf", pipelineTag = "text-generation",
    ),
    messages = (0 until messageCount).map { index ->
        ChatMessage(
            id = "benchmark-message-$index",
            role = if (index % 2 == 0) MessageRole.User else MessageRole.Assistant,
            text = if (index % 2 == 0) {
                "Question ${index / 2}: How does on-device inference keep a conversation private?"
            } else if (richText) {
                "Answer ${index / 2}: **On-device inference** keeps prompts local.\n\n" +
                    "## Memory and context\n\n" +
                    "- Weights remain in local storage.\n- Context grows with conversation history.\n" +
                    "- Smaller models leave room for rendering and other apps.\n\n" +
                    "```kotlin\nval tokens = model.generate(prompt)\ntokens.collect { token ->\n" +
                    "    conversation.append(token)\n}\n```\n\n" +
                    "> A longer conversation needs a larger context budget.\n\n" +
                    "Compare **latency**, *memory*, and `tokensPerSecond` together.\n\n" +
                    "Answer ${index / 2}: end of fixture."
            } else {
                "Answer ${index / 2}: The model processes your prompt on this device. " +
                    "Its weights stay in local storage and the conversation stays in memory.\n\n" +
                    "Longer conversations require more context memory. " +
                    "A smaller model leaves more room for the interface and other applications.\n\n" +
                    "This fixed response exercises text layout, message actions, and the frosted chat surfaces."
            },
            delivery = if (index % 2 == 1) MessageDelivery.Complete else null,
        )
    }.toPersistentList(),
)
