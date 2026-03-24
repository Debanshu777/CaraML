package com.debanshu777.caraml.core.drawer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.debanshu777.caraml.core.navigation.AppScreen

private val primaryDrawerItems = listOf(
    DrawerItem(
        id = "chat",
        title = "Chat",
        icon = Icons.Default.ChatBubbleOutline,
    ),
    DrawerItem(
        id = "models",
        title = "Models",
        icon = Icons.Default.Storage,
    ),
    DrawerItem(
        id = "settings",
        title = "Settings",
        icon = Icons.Default.Settings,
    ),
)

private val itemIdToScreen: Map<String, AppScreen> = mapOf(
    "chat" to AppScreen.Home,
    "models" to AppScreen.Search,
    "settings" to AppScreen.Settings,
)

private val primaryScreens = setOf<NavKey>(
    AppScreen.Home,
    AppScreen.Search,
    AppScreen.Settings,
)

private fun NavKey?.toDrawerItemId(): String? = when (this) {
    is AppScreen.Home -> "chat"
    is AppScreen.Search -> "models"
    is AppScreen.Settings -> "settings"
    else -> null
}

@Composable
fun AppDrawerShell(
    modifier: Modifier = Modifier,
    backStack: NavBackStack<NavKey>,
    content: @Composable () -> Unit,
) {
    val controller = remember { DrawerController() }

    CompositionLocalProvider(LocalDrawerController provides controller) {
        val currentScreen = backStack.lastOrNull()
        val selectedItemId = currentScreen.toDrawerItemId()
        val gestureEnabled = currentScreen in primaryScreens

        AnimatedDrawerScaffold(
            modifier = modifier,
            drawerState = controller.drawerState,
            onDrawerStateChange = { controller.setState(it) },
            gestureEnabled = gestureEnabled,
            drawerContent = {
                CustomDrawer(
                    items = primaryDrawerItems,
                    selectedItemId = selectedItemId,
                    onItemClick = { item ->
                        val screen = itemIdToScreen[item.id]
                        if (screen != null && currentScreen != screen) {
                            backStack.clear()
                            backStack.add(screen)
                        }
                        controller.close()
                    }
                )
            },
            content = content,
        )
    }
}
