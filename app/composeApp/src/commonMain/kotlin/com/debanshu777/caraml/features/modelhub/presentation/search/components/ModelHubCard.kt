package com.debanshu777.caraml.features.modelhub.presentation.search.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.debanshu777.caraml.core.ui.layout.LocalContentEndInset
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.tooling.preview.Preview
import com.debanshu777.caraml.core.theme.CaraMLTheme
import com.debanshu777.caraml.core.theme.ThemeMode
import com.debanshu777.caraml.core.theme.ThemePreferences
import com.debanshu777.caraml.core.ui.icons.AppIcons
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.core.ui.components.BrandButton
import com.debanshu777.caraml.core.ui.components.BrandButtonStyle

/** Shared hierarchy, wrapping metrics and separator for Discover and Library rows. */
@Composable
internal fun ModelHubCard(
    title: String,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    titleModifier: Modifier = Modifier,
    task: String? = null,
    leading: (@Composable () -> Unit)? = null,
    metrics: (@Composable () -> Unit)? = null,
    status: (@Composable () -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val spacing = AppTheme.spacing
    val separator = title.indexOf('/').takeIf { it > 0 && it < title.lastIndex }
    val owner = separator?.let { title.substring(0, it) }
    val name = separator?.let { title.substring(it + 1) } ?: title
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = modifier.fillMaxWidth()
                .background(if (emphasized) AppTheme.colors.surfaceContainerHigh else Color.Transparent)
                .padding(end = LocalContentEndInset.current)
                .padding(vertical = spacing.spacing8),
            horizontalArrangement = Arrangement.spacedBy(spacing.spacing8),
            verticalAlignment = Alignment.Top,
        ) {
            Box(Modifier.width(42.dp).align(Alignment.CenterVertically), contentAlignment = Alignment.Center) {
                if (leading != null) leading() else ModelMonogram(title, task = task)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.spacing4)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.spacing8),
                ) {
                    Text(
                        text = buildAnnotatedString {
                            owner?.let {
                                withStyle(SpanStyle(
                                    color = AppTheme.colors.onSurfaceVariant,
                                    fontSize = AppTheme.typography.bodyLarge.fontSize,
                                    fontWeight = FontWeight.Normal,
                                )) {
                                    append(it)
                                    append("/")
                                }
                            }
                            append(name)
                        },
                        style = AppTheme.typography.itemTitle,
                        color = AppTheme.colors.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = titleModifier.weight(1f, fill = false))
                }
                if (metrics != null || status != null) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(spacing.spacing12),
                        verticalArrangement = Arrangement.spacedBy(spacing.spacing4),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        metrics?.invoke()
                        status?.invoke()
                    }
                }
                task?.takeIf(String::isNotBlank)?.let {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = AppTheme.colors.surfaceContainerHigh,
                        contentColor = AppTheme.colors.onSurfaceVariant,
                    ) {
                        Text(humanReadableTask(it), style = AppTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = spacing.spacing8, vertical = spacing.spacing4))
                    }
                }
                action?.let { Box(Modifier.padding(top = spacing.spacing4)) { it() } }
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 42.dp + spacing.spacing12),
            color = AppTheme.colors.outlineVariant,
        )
    }
}

@Composable
internal fun ModelMetric(icon: ImageVector, value: String, description: String) {
    Row(
        modifier = Modifier.clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = AppTheme.colors.onSurfaceVariant)
        Text(value, style = AppTheme.typography.labelBase, color = AppTheme.colors.onSurfaceVariant)
    }
}

private fun humanReadableTask(tag: String): String = when (tag.lowercase()) {
    "image-text-to-text" -> "Image + text"
    "text-to-image" -> "Text → image"
    "image-to-image" -> "Image → image"
    "text-to-video" -> "Text → video"
    "image-to-video" -> "Image → video"
    else -> tag.replace('-', ' ').replace('_', ' ').replaceFirstChar { it.uppercase() }
}

@Composable
internal fun ModelHubAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    BrandButton(
        style = BrandButtonStyle.Secondary,
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = AppTheme.spacing.spacing48),
        contentPadding = PaddingValues(horizontal = AppTheme.spacing.spacing12, vertical = AppTheme.spacing.spacing8),
    ) { Text(label) }
}

@Composable
internal fun ModelMonogram(
    name: String,
    modifier: Modifier = Modifier,
    task: String? = null,
    size: Dp = 36.dp,
) {
    val label = name.substringAfterLast('/').firstOrNull { it.isLetterOrDigit() }
        ?.uppercaseChar()?.toString() ?: "M"
    ModelMonogramFrame(modifier = modifier, task = task, size = size) {
        Text(label, style = AppTheme.typography.heading24)
    }
}

/** Shared silhouette and task colors with a caller-provided square [size]. */
@Composable
internal fun ModelMonogramFrame(
    modifier: Modifier = Modifier,
    task: String? = null,
    size: Dp = 36.dp,
    content: @Composable () -> Unit,
) {
    val image = task?.lowercase() in setOf("text-to-image", "image-to-image", "unconditional-image-generation")
    val movingOrAudio = task?.let { it.contains("video", true) || it.contains("audio", true) } == true
    Surface(
        modifier = modifier.size(size).rotate(-6f).clearAndSetSemantics {},
        shape = RoundedCornerShape(12.dp),
        color = when { image -> AppTheme.brandColors.lilac; movingOrAudio -> AppTheme.brandColors.mint; else -> AppTheme.brandColors.yellow },
        contentColor = AppTheme.brandColors.ink,
    ) {
        Box(contentAlignment = Alignment.Center) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1f)) {
                content()
            }
        }
    }
}

@Preview(name = "Model rows · light", widthDp = 390)
@Composable
private fun ModelHubCardLightPreview() {
    ModelHubCardPreviewContent(ThemeMode.LIGHT)
}

@Preview(name = "Model rows · dark", widthDp = 390)
@Composable
private fun ModelHubCardDarkPreview() {
    ModelHubCardPreviewContent(ThemeMode.DARK)
}

@Preview(name = "Model rows · narrow, large text", widthDp = 320, fontScale = 2f)
@Composable
private fun ModelHubCardLargeTextPreview() {
    ModelHubCardPreviewContent(ThemeMode.LIGHT)
}

/** Local fixtures keep IDE previews independent of network, storage and recommendation services. */
@Composable
private fun ModelHubCardPreviewContent(themeMode: ThemeMode) {
    CaraMLTheme(ThemePreferences(themeMode = themeMode, reduceMotion = true)) {
        Surface(color = AppTheme.colors.background) {
            Column(Modifier.padding(horizontal = AppTheme.spacing.spacing16)) {
                ModelHubCard(
                    title = "Qwen/Qwen3.5-4B-Instruct-GGUF",
                    task = "image-text-to-text",
                    metrics = {
                        ModelMetric(AppIcons.Compute, "4B", "4 billion parameters")
                        ModelMetric(AppIcons.Download, "107K", "107 thousand downloads")
                        ModelMetric(AppIcons.Favorite, "47", "47 likes")
                    },
                    status = { ModelStatusIndicator("Likely fits", AppIcons.CheckCircle, AppTheme.colors.primary) },
                )
                ModelHubCard(
                    title = "Stable-Diffusion",
                    task = "text-to-image",
                    metrics = { ModelMetric(AppIcons.Storage, "2.42 GB", "2.42 GB on disk") },
                    status = { ModelStatusIndicator("Needs setup", AppIcons.Warning, AppTheme.colors.onSurface) },
                    action = { ModelHubAction("Finish setup", onClick = {}) },
                )
                ModelHubCard(
                    title = "Llama-3.2-1B-Instruct",
                    task = "text-generation",
                    emphasized = true,
                    leading = { Icon(AppIcons.Checkbox, contentDescription = "Selected", tint = AppTheme.actionColor) },
                    metrics = { ModelMetric(AppIcons.Storage, "860 MB", "860 MB on disk") },
                    status = { ModelStatusIndicator("Ready", AppIcons.CheckCircle, AppTheme.colors.primary) },
                )
            }
        }
    }
}
