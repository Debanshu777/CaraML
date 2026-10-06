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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.core.ui.components.BrandButton
import com.debanshu777.caraml.core.ui.components.BrandButtonStyle

/** The same open model row is used for Discover and the local library. */
@Composable
internal fun ModelHubCard(
    title: String,
    eyebrow: String?,
    metadata: String?,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    task: String? = null,
    leading: (@Composable () -> Unit)? = null,
    badge: (@Composable () -> Unit)? = null,
    status: (@Composable () -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val spacing = AppTheme.spacing
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = modifier.fillMaxWidth()
                .background(if (emphasized) AppTheme.colors.surfaceContainerHigh else Color.Transparent)
                .padding(vertical = spacing.spacing24),
            verticalArrangement = Arrangement.spacedBy(spacing.spacing12),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.spacing12),
            ) {
                if (leading != null) {
                    Box(Modifier.size(AppTheme.dimensions.size40), contentAlignment = Alignment.Center) { leading() }
                } else {
                    ModelMonogram(title, task = task)
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.spacing4)) {
                    Text(
                        text = title,
                        style = AppTheme.typography.itemTitle,
                        color = AppTheme.colors.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    eyebrow?.takeIf(String::isNotBlank)?.let {
                        Text(
                            text = it,
                            style = AppTheme.typography.labelBase,
                            color = AppTheme.colors.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (!metadata.isNullOrBlank() || badge != null) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing.spacing8),
                    verticalArrangement = Arrangement.spacedBy(spacing.spacing4),
                ) {
                    metadata?.takeIf(String::isNotBlank)?.let {
                        Text(it, style = AppTheme.typography.bodySmall, color = AppTheme.colors.onSurface)
                    }
                    badge?.invoke()
                }
            }
            if (status != null || action != null) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if (LocalDensity.current.fontScale >= 1.5f || maxWidth < 280.dp) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(spacing.spacing12),
                            verticalArrangement = Arrangement.spacedBy(spacing.spacing8),
                            itemVerticalAlignment = Alignment.CenterVertically,
                        ) {
                            status?.invoke()
                            action?.invoke()
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f).padding(end = spacing.spacing8)) { status?.invoke() }
                            action?.invoke()
                        }
                    }
                }
            }
        }
    }
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
internal fun ModelMonogram(name: String, modifier: Modifier = Modifier, task: String? = null) {
    val label = name.substringAfterLast('/').firstOrNull { it.isLetterOrDigit() }
        ?.uppercaseChar()?.toString() ?: "M"
    val image = task?.lowercase() in setOf("text-to-image", "image-to-image", "unconditional-image-generation")
    val movingOrAudio = task?.let { it.contains("video", true) || it.contains("audio", true) } == true
    Surface(
        modifier = modifier.width(42.dp).height(45.dp).rotate(if (image) 6f else -5f).clearAndSetSemantics {},
        shape = RoundedCornerShape(topStart = 13.dp, topEnd = 17.dp, bottomEnd = 13.dp, bottomStart = 9.dp),
        color = when { image -> AppTheme.brandColors.lilac; movingOrAudio -> AppTheme.brandColors.mint; else -> AppTheme.brandColors.yellow },
        contentColor = AppTheme.brandColors.ink,
    ) {
        Box(contentAlignment = Alignment.Center) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1f)) {
                Text(label, style = AppTheme.typography.heading24)
            }
        }
    }
}
