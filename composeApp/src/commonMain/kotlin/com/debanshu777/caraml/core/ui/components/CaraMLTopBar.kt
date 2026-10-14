package com.debanshu777.caraml.core.ui.components

import com.debanshu777.caraml.core.ui.icons.AppIcons
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.core.ui.layout.AppContentKind
import com.debanshu777.caraml.core.ui.layout.ResponsiveContentPane
import com.debanshu777.caraml.core.drawer.LocalNavigationMenuAction

enum class TopBarNavigation {
    None,
    Menu,
    Back,
}

@Composable
fun CaraMLTopBar(
    title: String,
    navigation: TopBarNavigation,
    onNavigationClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    CaraMLTopBar(
        title = title,
        navigation = navigation,
        onNavigationClick = onNavigationClick,
        contentKind = AppContentKind.Details,
        modifier = modifier,
        actions = actions,
    )
}

@Composable
fun CaraMLTopBar(
    title: String,
    navigation: TopBarNavigation,
    onNavigationClick: (() -> Unit)?,
    contentKind: AppContentKind,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    require((navigation == TopBarNavigation.None) == (onNavigationClick == null))

    ResponsiveContentPane(
        kind = contentKind,
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
        fillMaxHeight = false,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = AppTheme.spacing.spacing64),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (navigation) {
                TopBarNavigation.None -> Unit
                TopBarNavigation.Menu -> HeaderNavigationButton(
                    contentDescription = "Open navigation menu",
                    onClick = requireNotNull(onNavigationClick),
                ) {
                    Icon(
                        imageVector = AppIcons.Menu,
                        contentDescription = null,
                        tint = AppTheme.colors.onSurface,
                    )
                }
                TopBarNavigation.Back -> HeaderNavigationButton(
                    contentDescription = "Navigate back",
                    onClick = requireNotNull(onNavigationClick),
                ) {
                    Icon(
                        imageVector = AppIcons.Back,
                        contentDescription = null,
                        tint = AppTheme.colors.onSurface,
                    )
                }
            }
            if (navigation != TopBarNavigation.None) Spacer(Modifier.width(AppTheme.spacing.spacing8))
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = AppTheme.typography.pageTitle,
                color = AppTheme.colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            actions()
        }
    }
}

@Composable
fun CaraMLPrimaryTopBar(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    CaraMLPrimaryTopBar(
        title = title,
        contentKind = AppContentKind.Settings,
        modifier = modifier,
        actions = actions,
    )
}

@Composable
fun CaraMLPrimaryTopBar(
    title: String,
    contentKind: AppContentKind,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val menuAction = LocalNavigationMenuAction.current
    CaraMLTopBar(
        title = title,
        navigation = if (menuAction == null) TopBarNavigation.None else TopBarNavigation.Menu,
        onNavigationClick = menuAction,
        modifier = modifier,
        contentKind = contentKind,
        actions = actions,
    )
}

@Composable
private fun HeaderNavigationButton(
    contentDescription: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    BrandIconButton(
        onClick = onClick,
        modifier = Modifier
            .size(AppTheme.spacing.spacing48)
            .semantics { this.contentDescription = contentDescription },
        content = icon,
    )
}
