package com.debanshu777.caraml.core.drawer

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.debanshu777.caraml.core.navigation.AppScreen
import com.debanshu777.caraml.features.chat.domain.GenerationMode

private val primaryDrawerItems = listOf(
    DrawerItem(
        id = "chat",
        title = "Chat",
        icon = Icons.Default.ChatBubbleOutline,
    ),
    DrawerItem(
        id = "image",
        title = "Image",
        icon = Icons.Default.Image,
    ),
    DrawerItem(
        id = "video",
        title = "Video",
        icon = Icons.Default.Videocam,
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

private val primaryScreens = setOf<NavKey>(
    AppScreen.Home,
    AppScreen.Search,
    AppScreen.Settings,
)

@Composable
fun AppDrawerShell(
    modifier: Modifier = Modifier,
    backStack: NavBackStack<NavKey>,
    content: @Composable () -> Unit,
) {
    val controller = remember { DrawerController() }
    val modeController = remember { GenerationModeController() }

    CompositionLocalProvider(
        LocalDrawerController provides controller,
        LocalGenerationModeController provides modeController,
    ) {
        val currentScreen = backStack.lastOrNull()
        val gestureEnabled = currentScreen in primaryScreens

        val selectedItemId = when {
            currentScreen is AppScreen.Home -> when (modeController.mode) {
                GenerationMode.Text -> "chat"
                GenerationMode.Image -> "image"
                GenerationMode.Video -> "video"
            }
            currentScreen is AppScreen.Search -> "models"
            currentScreen is AppScreen.Settings -> "settings"
            else -> null
        }

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
                        when (item.id) {
                            "chat" -> {
                                modeController.setState(GenerationMode.Text)
                                if (currentScreen != AppScreen.Home) {
                                    backStack.clear()
                                    backStack.add(AppScreen.Home)
                                }
                            }
                            "image" -> {
                                modeController.setState(GenerationMode.Image)
                                if (currentScreen != AppScreen.Home) {
                                    backStack.clear()
                                    backStack.add(AppScreen.Home)
                                }
                            }
                            "video" -> {
                                modeController.setState(GenerationMode.Video)
                                if (currentScreen != AppScreen.Home) {
                                    backStack.clear()
                                    backStack.add(AppScreen.Home)
                                }
                            }
                            "models" -> {
                                if (currentScreen != AppScreen.Search) {
                                    backStack.clear()
                                    backStack.add(AppScreen.Search)
                                }
                            }
                            "settings" -> {
                                if (currentScreen != AppScreen.Settings) {
                                    backStack.clear()
                                    backStack.add(AppScreen.Settings)
                                }
                            }
                        }
                        controller.close()
                    }
                )
            },
            content = content,
        )
    }
}
