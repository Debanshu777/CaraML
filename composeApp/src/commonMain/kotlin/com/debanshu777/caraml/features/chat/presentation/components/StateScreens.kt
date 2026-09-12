package com.debanshu777.caraml.features.chat.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.AuroraSurfaceLevel
import com.debanshu777.caraml.core.ui.components.CaraMLEmptyState
import com.debanshu777.caraml.core.ui.components.CaraMLPane
import com.debanshu777.caraml.features.chat.domain.GenerationMode
import com.debanshu777.caraml.features.chat.presentation.components.providers.ErrorMessagePreviewProvider

@Preview
@Composable
private fun NoCompatibleModelsScreenPreview() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            NoCompatibleModelsScreen(
                mode = GenerationMode.Image,
                onDownloadModelClick = {},
            )
        }
    }
}

@Preview
@Composable
private fun NoModelsScreenPreview() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            NoModelsScreen(onDownloadModelClick = {})
        }
    }
}

@Preview
@Composable
private fun ModelLoadingScreenPreview() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            ModelLoadingScreen()
        }
    }
}

@Preview
@Composable
private fun ModelErrorScreenPreview(
    @PreviewParameter(ErrorMessagePreviewProvider::class) errorMessage: String
) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            ModelErrorScreen(
                errorMessage = errorMessage,
                onTryAnotherModelClick = {}
            )
        }
    }
}

@Composable
fun NoCompatibleModelsScreen(
    mode: GenerationMode,
    onDownloadModelClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (title, subtitle) = when (mode) {
        GenerationMode.Text ->
            "No chat models downloaded" to "Download a language model (GGUF) to use text chat"
        GenerationMode.Image ->
            "No image models downloaded" to "Download a diffusion checkpoint to generate images"
        GenerationMode.Video ->
            "No video models downloaded" to "Download a diffusion checkpoint that supports video"
    }
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CaraMLEmptyState(
            icon = Icons.Default.AutoAwesome,
            title = title,
            supportingText = subtitle,
            actionLabel = "Browse models",
            onAction = onDownloadModelClick,
        )
    }
}

@Composable
fun NoModelsScreen(
    onDownloadModelClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CaraMLEmptyState(
            icon = Icons.Default.Download,
            title = "No models downloaded yet",
            supportingText = "Download a model to start chatting",
            actionLabel = "Download Model",
            onAction = onDownloadModelClick,
        )
    }
}

@Composable
fun ModelLoadingScreen(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CaraMLPane(level = AuroraSurfaceLevel.Pane) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator()
                Text("Loading model...")
            }
        }
    }
}

@Composable
fun ModelErrorScreen(
    errorMessage: String,
    onTryAnotherModelClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CaraMLEmptyState(
            icon = Icons.Default.Error,
            title = "Unable to load model",
            supportingText = errorMessage,
            actionLabel = "Try Another Model",
            onAction = onTryAnotherModelClick,
        )
    }
}
