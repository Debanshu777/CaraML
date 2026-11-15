package com.debanshu777.caraml.features.modelhub.presentation.search.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.debanshu777.caraml.core.theme.AppTheme

/** Author text is announced by the containing card; the avatar is decorative. */
@Composable
internal fun ModelAuthorAvatar(
    avatarUrl: String?,
    author: String,
    modifier: Modifier = Modifier,
    imageLoader: ImageLoader? = null,
    task: String? = null,
    size: Dp = 36.dp,
) {
    var imageLoaded by remember(avatarUrl) { mutableStateOf(false) }
    ModelMonogramFrame(
        modifier = modifier.testTag("model-author-avatar"),
        task = task,
        size = size,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // Successful SVG/PNG avatars may be transparent, so remove the fallback after loading.
            if (!imageLoaded) {
                Text(
                    text = author.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?",
                    style = AppTheme.typography.heading24,
                    color = AppTheme.brandColors.ink,
                )
            }
            avatarUrl?.takeIf(String::isNotBlank)?.let { url ->
                AsyncImage(
                    model = url,
                    imageLoader = imageLoader ?: SingletonImageLoader.get(LocalPlatformContext.current),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onLoading = { imageLoaded = false },
                    onSuccess = { imageLoaded = true },
                    onError = { imageLoaded = false },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
