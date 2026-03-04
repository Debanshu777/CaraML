package com.debanshu777.flash.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.debanshu777.flash.platform.PlatformPaths
import com.debanshu777.huggingfacemanager.download.StoragePathProvider
import com.debanshu777.runner.LlamaRunner
import com.debanshu777.runner.generateFlowTokens
import kotlin.io.println
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LOG_TAG = "ChatScreen"

private val llamaDispatcher = Dispatchers.IO.limitedParallelism(1)

private fun log(phase: String, msg: String, detail: String? = null) {
    val full = if (detail != null) "$msg | $detail" else msg
    println("[$LOG_TAG] $phase: $full")
}

private fun truncate(s: String, maxLen: Int = 60): String =
    if (s.length <= maxLen) s else s.substring(0, maxLen) + "..."

enum class MessageRole {
    User,
    Assistant
}

data class ChatMessage(
    val role: MessageRole,
    val text: String
)

@Composable
fun ChatScreen(
    modelPath: String,
    modelId: String,
    onBack: () -> Unit,
    storagePathProvider: StoragePathProvider,
    modifier: Modifier = Modifier
) {
    val messages = remember { mutableStateListOf<ChatMessage>() }
    var inputText by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }
    var isModelLoaded by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Initialize runner
    val runner = remember { LlamaRunner() }

    LaunchedEffect(modelPath) {
        log("load", "modelPath=${truncate(modelPath)}")
        withContext(llamaDispatcher) {
            try {
                if (modelPath.isBlank()) {
                    log("load", "ERROR", "model path is blank")
                    loadError = "Model path is invalid"
                    return@withContext
                }
                if (!storagePathProvider.isModelFileReadable(modelPath)) {
                    log("load", "ERROR", "file not found or not readable")
                    loadError =
                        "Model file not found or not readable. It may have been moved or deleted."
                    return@withContext
                }
                val nativeLibDir = PlatformPaths.getNativeLibDir()
                if (nativeLibDir.isBlank()) {
                    log("load", "ERROR", "native lib dir is empty")
                    loadError = "Failed to initialize. Please restart the app."
                    return@withContext
                }
                runner.initialize(nativeLibDir)
                val loaded = runner.loadModel(
                    modelPath = modelPath,
                    nGpuLayers = PlatformPaths.getDefaultGpuLayers()
                )
                log("load", if (loaded) "success" else "failed", "path=$modelPath")
                isModelLoaded = loaded
                if (loaded) {
                    val spRet = runner.processSystemPrompt("You are a helpful assistant.")
                    if (spRet != 0) {
                        loadError = "Failed to initialize conversation context."
                        isModelLoaded = false
                    }
                } else {
                    loadError =
                        "Failed to load model. The file may be corrupted or in an unsupported format. Try a different model."
                }
            } catch (e: Exception) {
                log("load", "ERROR", "exception=${e.message}")
                loadError = "An error occurred while loading the model"
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                runner.unloadModel()
                runner.shutdown()
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            TextButton(onClick = onBack) {
                Text("← Back")
            }
        }

        Text(
            text = modelId.substringAfterLast("/"),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        // Show loading or error status
        if (!isModelLoaded && loadError == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator()
                    Text("Loading model...")
                }
            }
        }

        if (loadError != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = loadError ?: "",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        if (messages.isEmpty() && isModelLoaded) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Send a message to get started",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else if (messages.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages) { message ->
                    MessageBubble(message)
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type a message...") },
                maxLines = 4,
                enabled = isModelLoaded && !isGenerating
            )
            if (isGenerating) {
                IconButton(
                    onClick = { runner.cancelGenerate() },
                    enabled = true
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Stop generating"
                    )
                }
            } else {
                IconButton(
                    onClick = {
                        if (inputText.isNotBlank() && isModelLoaded) {
                            val userMessage = inputText.trim()
                            log(
                                "input",
                                "user_msg_len=${userMessage.length}",
                                "preview=${truncate(userMessage)}"
                            )
                            messages.add(ChatMessage(MessageRole.User, userMessage))
                            inputText = ""
                            isGenerating = true

                            val assistantIndex = messages.size
                            messages.add(ChatMessage(MessageRole.Assistant, ""))

                            scope.launch(llamaDispatcher) {
                                try {
                                    log("generate", "start", "maxTokens=1024")
                                    val ret =
                                        runner.processUserPrompt(userMessage, predictLength = 1024)
                                    if (ret != 0) {
                                        withContext(Dispatchers.Main) {
                                            messages[assistantIndex] =
                                                ChatMessage(
                                                    MessageRole.Assistant,
                                                    "Failed to process message."
                                                )
                                        }
                                        return@launch
                                    }
                                    runner.generateFlowTokens().collect { token ->
                                        withContext(Dispatchers.Main) {
                                            val current = messages[assistantIndex]
                                            messages[assistantIndex] =
                                                current.copy(text = current.text + token)
                                        }
                                    }
                                    log("output", "done")
                                } catch (e: Exception) {
                                    log("generate", "ERROR", "exception=${e.message}")
                                    withContext(Dispatchers.Main) {
                                        messages[assistantIndex] =
                                            ChatMessage(MessageRole.Assistant, "An error occurred.")
                                    }
                                } finally {
                                    runner.finalizeGeneration()
                                    withContext(Dispatchers.Main) {
                                        isGenerating = false
                                    }
                                }
                            }
                        }
                    },
                    enabled = inputText.isNotBlank() && isModelLoaded
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send message"
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier
) {
    val isUser = message.role == MessageRole.User
    val alignment = if (isUser) TextAlign.End else TextAlign.Start
    val backgroundColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val textColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }

    Box(
        modifier = modifier.fillMaxWidth(),
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = backgroundColor,
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
                textAlign = alignment,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}
