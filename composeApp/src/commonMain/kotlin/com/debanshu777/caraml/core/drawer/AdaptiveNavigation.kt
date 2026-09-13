package com.debanshu777.caraml.core.drawer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.AuroraSurfaceLevel
import com.debanshu777.caraml.core.ui.components.CaraMLPane
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
) {
    CaraMLPane(
        modifier = modifier.fillMaxHeight(),
        level = AuroraSurfaceLevel.Recessed,
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
        }
    }
}

@Composable
fun AdaptiveNavigation(
    navigation: AppNavigationLayout,
    items: List<DrawerItem>,
    selectedItemId: String?,
    drawerState: CustomDrawerState,
    onDrawerStateChange: (CustomDrawerState) -> Unit,
    gestureEnabled: Boolean,
    onItemClick: (DrawerItem) -> Unit,
    modifier: Modifier = Modifier,
    navigationInsets: WindowInsets = WindowInsets.safeDrawing,
    content: @Composable () -> Unit,
) {
    when (navigation) {
        AppNavigationLayout.ModalDrawer -> AnimatedDrawerScaffold(
            modifier = modifier,
            drawerState = drawerState,
            onDrawerStateChange = onDrawerStateChange,
            gestureEnabled = gestureEnabled,
            drawerContent = {
                AppNavigationPanel(
                    items = items,
                    selectedItemId = selectedItemId,
                    compact = false,
                    onItemClick = onItemClick,
                    modifier = Modifier.fillMaxWidth(0.80f),
                    contentInsets = navigationInsets,
                )
            },
            content = content,
        )

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
                modifier = Modifier.width(240.dp),
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
    }
}
