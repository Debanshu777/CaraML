package com.debanshu777.caraml.core.ui.components

import com.debanshu777.caraml.core.ui.icons.AppIcons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    navigationIcon: ImageVector = AppIcons.Menu,
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
                style = AppTheme.typography.pageTitle,
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

/** Shared raised navigation action with a stable 48dp touch target. */
@Composable
fun BrandNavigationButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: ImageVector = AppIcons.Menu,
    contentDescription: String = "Open navigation menu",
) {
    BrandIconButton(
        onClick = onClick,
        modifier = modifier.size(AppTheme.spacing.spacing48)
            .semantics { this.contentDescription = contentDescription },
    ) {
        Icon(imageVector = navigationIcon, contentDescription = null)
    }
}
