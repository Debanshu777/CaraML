package com.debanshu777.caraml.core.ui.components

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.ui.layout.AppContentKind
import com.debanshu777.caraml.core.ui.layout.ResponsiveContentPane
import com.debanshu777.caraml.core.theme.prism
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
                .heightIn(min = 64.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (navigation) {
                TopBarNavigation.None -> Unit
                TopBarNavigation.Menu -> HeaderNavigationButton(
                    contentDescription = "Open navigation menu",
                    onClick = requireNotNull(onNavigationClick),
                ) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = null,
                    )
                }
                TopBarNavigation.Back -> HeaderNavigationButton(
                    contentDescription = "Navigate back",
                    onClick = requireNotNull(onNavigationClick),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                    )
                }
            }
            if (navigation != TopBarNavigation.None) Spacer(Modifier.width(8.dp))
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.prism.screenTitle,
                color = MaterialTheme.colorScheme.onSurface,
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
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(48.dp)
            .semantics { this.contentDescription = contentDescription },
        content = icon,
    )
}
