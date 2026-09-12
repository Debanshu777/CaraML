package com.debanshu777.caraml.core.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

enum class TopBarNavigation {
    None,
    Menu,
    Back,
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun CaraMLTopBar(
    title: String,
    navigation: TopBarNavigation,
    onNavigationClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    require((navigation == TopBarNavigation.None) == (onNavigationClick == null))

    TopAppBar(
        modifier = modifier,
        title = { Text(title) },
        navigationIcon = {
            when (navigation) {
                TopBarNavigation.None -> Unit
                TopBarNavigation.Menu -> IconButton(onClick = requireNotNull(onNavigationClick)) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = "Open navigation menu",
                    )
                }
                TopBarNavigation.Back -> IconButton(onClick = requireNotNull(onNavigationClick)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Navigate back",
                    )
                }
            }
        },
        actions = actions,
    )
}
