package com.debanshu777.caraml.core.drawer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.AuroraSurfaceLevel
import com.debanshu777.caraml.core.theme.auroraColors
import com.debanshu777.caraml.core.ui.components.CaraMLPane
import com.debanshu777.caraml.core.ui.components.SignalRail
import com.debanshu777.caraml.core.ui.components.SignalTone
import com.debanshu777.caraml.core.ui.layout.AppNavigationLayout

@Composable
fun AppNavigationPanel(
    items: List<DrawerItem>,
    selectedItemId: String?,
    compact: Boolean,
    onItemClick: (DrawerItem) -> Unit,
    modifier: Modifier = Modifier,
    contentInsets: WindowInsets = WindowInsets(0),
    contentInsetSides: WindowInsetsSides = WindowInsetsSides.Top,
    surfaceLevel: AuroraSurfaceLevel = AuroraSurfaceLevel.Recessed,
    contextualItems: List<DrawerItem> = emptyList(),
    selectedContextualItemId: String? = null,
    onContextualItemClick: (DrawerItem) -> Unit = {},
) {
    CaraMLPane(
        modifier = modifier.fillMaxHeight(),
        level = surfaceLevel,
        shape = RectangleShape,
        showBorder = false,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(contentInsets.only(contentInsetSides))
                .verticalScroll(rememberScrollState()),
        ) {
            if (!compact) {
                Text(
                    text = "CaraML",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            } else {
                Spacer(modifier = Modifier.height(16.dp))
            }

            items.forEach { item ->
                DrawerItemView(
                    item = item,
                    selected = item.id == selectedItemId,
                    showLabel = !compact,
                    onClick = { onItemClick(item) },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }

            if (!compact && contextualItems.isNotEmpty()) {
                Text(
                    text = "Create modes",
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                contextualItems.forEach { item ->
                    DrawerItemView(
                        item = item,
                        selected = item.id == selectedContextualItemId,
                        showLabel = true,
                        onClick = { onContextualItemClick(item) },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun AdaptiveNavigation(
    navigation: AppNavigationLayout,
    items: List<DrawerItem>,
    selectedItemId: String?,
    onItemClick: (DrawerItem) -> Unit,
    modifier: Modifier = Modifier,
    navigationInsets: WindowInsets = WindowInsets.safeDrawing,
    contextualItems: List<DrawerItem> = emptyList(),
    selectedContextualItemId: String? = null,
    onContextualItemClick: (DrawerItem) -> Unit = {},
    content: @Composable () -> Unit,
) {
    when (navigation) {
        AppNavigationLayout.BottomBar -> Column(modifier = modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                content()
            }
            AppBottomNavigationBar(
                items = items,
                selectedItemId = selectedItemId,
                onItemClick = onItemClick,
                navigationInsets = navigationInsets,
            )
        }

        AppNavigationLayout.Rail -> Row(modifier = modifier.fillMaxSize()) {
            AppNavigationPanel(
                items = items,
                selectedItemId = selectedItemId,
                compact = true,
                onItemClick = onItemClick,
                modifier = Modifier.width(80.dp),
                contentInsets = navigationInsets,
                contentInsetSides = WindowInsetsSides.Vertical,
            )
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f),
            ) {
                content()
            }
        }

        AppNavigationLayout.Sidebar -> Row(modifier = modifier.fillMaxSize()) {
            AppNavigationPanel(
                items = items,
                selectedItemId = selectedItemId,
                compact = false,
                onItemClick = onItemClick,
                modifier = Modifier.width(256.dp),
                contentInsets = navigationInsets,
                contentInsetSides = WindowInsetsSides.Vertical,
                contextualItems = contextualItems,
                selectedContextualItemId = selectedContextualItemId,
                onContextualItemClick = onContextualItemClick,
            )
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun AppBottomNavigationBar(
    items: List<DrawerItem>,
    selectedItemId: String?,
    onItemClick: (DrawerItem) -> Unit,
    navigationInsets: WindowInsets,
) {
    val colors = MaterialTheme.auroraColors
    NavigationBar(
        modifier = Modifier.fillMaxWidth(),
        containerColor = colors.commandSurface,
        windowInsets = navigationInsets.only(WindowInsetsSides.Bottom),
    ) {
        items.forEach { item ->
            val selected = item.id == selectedItemId
            NavigationBarItem(
                selected = selected,
                onClick = { onItemClick(item) },
                modifier = Modifier.semantics(mergeDescendants = true) {
                    contentDescription = if (selected) "${item.title}, selected" else item.title
                    this.selected = selected
                },
                icon = {
                    Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                        if (selected) {
                            SignalRail(tone = SignalTone.Accent)
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Icon(
                            imageVector = item.icon,
                            contentDescription = null,
                        )
                    }
                },
                label = { Text(item.title) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    indicatorColor = colors.selectedSurface,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}
