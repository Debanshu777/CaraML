package com.debanshu777.caraml.features.chat.presentation.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import com.debanshu777.caraml.core.ui.components.CommandSurface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.core.theme.AuroraSurfaceLevel
import com.debanshu777.caraml.core.ui.components.CaraMLEmptyState
import com.debanshu777.caraml.core.ui.components.CaraMLPane
import com.debanshu777.caraml.core.ui.components.BrandPal
import com.debanshu777.caraml.core.ui.components.BrandPalState
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
    CaraMLEmptyState(
        icon = Icons.Default.AutoAwesome,
        title = title,
        supportingText = subtitle,
        actionLabel = "Browse models",
        onAction = onDownloadModelClick,
        modifier = modifier,
    )
}

@Composable
fun NoModelsScreen(
    onDownloadModelClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    CaraMLEmptyState(
        icon = Icons.Default.Download,
        title = "No models downloaded yet",
        supportingText = "Download a model to start chatting",
        actionLabel = "Download Model",
        onAction = onDownloadModelClick,
        modifier = modifier,
    )
}

@Composable
fun ModelLoadingScreen(modifier: Modifier = Modifier) {
    CommandSurface(
        focused = false, active = true,
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        contentPadding = PaddingValues(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BrandPal(BrandPalState.Loading, Modifier.size(40.dp))
            Text("Waking up your model", style = AppTheme.typography.activityTitle21)
            Text("Loading model...", style = AppTheme.typography.bodySmall, color = AppTheme.colors.onSurfaceVariant)
            Text("Getting everything ready for your idea.", style = AppTheme.typography.bodySmall, color = AppTheme.colors.onSurfaceVariant)
        }
    }
}

@Composable
fun ModelErrorScreen(
    errorMessage: String,
    onTryAnotherModelClick: () -> Unit,
    onRetryCurrentModelClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    CommandSurface(
        focused = false, active = false,
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) { stateDescription = "Error" },
        contentPadding = PaddingValues(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BrandPal(BrandPalState.Error, Modifier.size(40.dp))
            Text("A little snag", style = AppTheme.typography.stateTitle26, color = AppTheme.colors.onSurface)
            Text("Unable to load model", style = AppTheme.typography.labelLarge, color = AppTheme.colors.onSurface)
            Text(errorMessage, style = AppTheme.typography.bodyBase, color = AppTheme.colors.onSurfaceVariant)
            if (onRetryCurrentModelClick != null) {
                Button(onClick = onRetryCurrentModelClick, modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) {
                    Text("Retry current model")
                }
            }
            OutlinedButton(onClick = onTryAnotherModelClick, modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) {
                Text("Try Another Model")
            }
        }
    }
}
