package com.debanshu777.caraml.features.modelhub.presentation.details.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import coil3.compose.AsyncImagePainter
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.sdk.discovery.huggingface.model.ModelCardDocument
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import com.mikepenz.markdown.model.ImageWidth
import com.mikepenz.markdown.model.PlaceholderConfig

internal class ModelCardImageTransformer(private val document: ModelCardDocument) : ImageTransformer by Coil3ImageTransformerImpl {
    override fun placeholderConfig(
        link: String, density: Density, containerSize: Size, imageWidth: ImageWidth,
        imageSize: Size, imageSizeChanged: ((String, Size) -> Unit)?,
    ): PlaceholderConfig = with(density) {
        // A standalone image paragraph can initially measure 0px high. Its own height must
        // come from the image, not that empty paragraph, or it can never become visible.
        val availableWidth = containerSize.takeIf { it.isSpecified && it.width > 0f }?.width ?: 320.dp.toPx()
        val natural = imageSize.takeIf { it.isSpecified && it.width > 0f && it.height > 0f }
            ?: Size(availableWidth, 56.dp.toPx() * fontScale)
        val width = if (imageWidth == ImageWidth.MAX_WIDTH) availableWidth else minOf(availableWidth, natural.width)
        PlaceholderConfig(Size(width.toDp().value, (natural.height * width / natural.width).toDp().value))
    }

    @Composable
    override fun transform(link: String): ImageData? = document.resolveCardUrl(link, image = true)?.let {
        Coil3ImageTransformerImpl.transform(it).copy(modifier = Modifier.fillMaxWidth())
    }

    @Composable
    override fun intrinsicSize(painter: Painter): Size {
        val state = (painter as? AsyncImagePainter)?.state?.collectAsState()?.value
        if (state !is AsyncImagePainter.State.Success) {
            // Give loading/error feedback a compact, font-scaled slot instead of a blank square.
            return with(LocalDensity.current) { Size(320.dp.toPx(), (if (state is AsyncImagePainter.State.Error) 112.dp else 56.dp).toPx() * fontScale) }
        }
        return Coil3ImageTransformerImpl.intrinsicSize(painter)
    }
}

@Composable
internal fun ModelCardImage(link: String, alt: String?, transformer: ImageTransformer, modifier: Modifier = Modifier) {
    val data = transformer.transform(link)
    val state = (data?.painter as? AsyncImagePainter)?.state?.collectAsState()?.value
    if (data != null && state is AsyncImagePainter.State.Success) {
        Image(data.painter, contentDescription = alt?.takeIf(String::isNotBlank) ?: "Model card illustration",
            modifier = modifier.fillMaxSize(), contentScale = data.contentScale)
    } else {
        val handler = LocalUriHandler.current
        ModelCardImageFeedback(alt, loading = data != null && state !is AsyncImagePainter.State.Error,
            onOpen = { handler.openUri(link) }, modifier = modifier.fillMaxSize())
    }
}

@Composable
internal fun ModelCardImageFeedback(alt: String?, loading: Boolean, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier, shape = AppTheme.shapes.small, color = AppTheme.colors.surfaceContainerLow) {
        Column(Modifier.padding(AppTheme.spacing.spacing8), verticalArrangement = Arrangement.Center) {
            Text(if (loading) "Loading image…" else "Image unavailable" + (alt?.takeIf(String::isNotBlank)?.let { ": $it" } ?: ""),
                style = AppTheme.typography.labelBase, color = AppTheme.colors.onSurfaceVariant)
            if (!loading) TextButton(onClick = onOpen) {
                Text("Open image", style = AppTheme.typography.labelLarge, color = AppTheme.colors.onSurface)
            }
        }
    }
}
