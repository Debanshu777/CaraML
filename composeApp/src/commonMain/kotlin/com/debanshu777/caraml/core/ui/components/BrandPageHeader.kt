package com.debanshu777.caraml.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.drawer.LocalNavigationMenuAction
import com.debanshu777.caraml.core.theme.AppTheme

/** Inline page heading. The containing page owns content gutters and safe drawing insets. */
@Composable
fun BrandPageHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    navigationAction: (() -> Unit)? = LocalNavigationMenuAction.current,
    navigationIcon: ImageVector = Icons.Default.Menu,
    navigationContentDescription: String = "Open navigation menu",
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (navigationAction != null) {
            BrandNavigationButton(
                onClick = navigationAction,
                navigationIcon = navigationIcon,
                contentDescription = navigationContentDescription,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                modifier = Modifier.semantics { heading() },
                style = AppTheme.typography.pageTitle32,
                color = AppTheme.colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = AppTheme.typography.bodySmall,
                    color = AppTheme.colors.onSurfaceVariant,
                )
            }
        }
        actions()
    }
}

/** A 44dp visual inside a 48dp touch target, shared by page headings and the floating Create menu. */
@Composable
fun BrandNavigationButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: ImageVector = Icons.Default.Menu,
    contentDescription: String = "Open navigation menu",
) {
    Box(
        modifier = modifier.size(48.dp)
            .semantics { this.contentDescription = contentDescription }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val shape = RoundedCornerShape(14.dp)
        Box(
            modifier = Modifier.size(44.dp)
                .then(if (AppTheme.softEffects) Modifier.shadow(
                    4.dp, shape, clip = false,
                    ambientColor = Color.Black.copy(alpha = 0.05f),
                    spotColor = Color.Black.copy(alpha = 0.05f),
                ) else Modifier)
                .clip(shape)
                .background(AppTheme.colors.surface.copy(alpha = if (AppTheme.softEffects) 0.82f else 1f))
                .border(1.dp, lerp(AppTheme.colors.outlineVariant, AppTheme.colors.surface, 0.7f), shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = navigationIcon, contentDescription = null, tint = AppTheme.colors.onSurface)
        }
    }
}
