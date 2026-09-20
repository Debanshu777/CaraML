package com.debanshu777.caraml.core.drawer

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.debanshu777.caraml.core.navigation.AppScreen
import com.debanshu777.caraml.core.ui.layout.AppContentKind
import com.debanshu777.caraml.core.ui.layout.AppNavigationLayout
import com.debanshu777.caraml.core.ui.layout.LocalAppNavigationLayout
import com.debanshu777.caraml.core.ui.layout.adaptiveLayoutPolicy
import com.debanshu777.caraml.features.chat.domain.GenerationMode

/** Full app-window width before persistent navigation consumes horizontal space. */
internal val LocalAppWindowWidth = compositionLocalOf<Dp?> { null }

private val primaryNavigationItems = listOf(
    DrawerItem(
        id = "create",
        title = "Create",
        icon = Icons.Default.AutoAwesome,
    ),
    DrawerItem(
        id = "models",
        title = "Models",
        icon = Icons.Default.Storage,
    ),
)

private val utilityNavigationItems = listOf(
    DrawerItem(
        id = "settings",
        title = "Settings",
        icon = Icons.Default.Settings,
    ),
)

private val generationModeItems = listOf(
    DrawerItem(
        id = "mode-text",
        title = "Text",
        icon = Icons.Default.ChatBubbleOutline,
    ),
    DrawerItem(
        id = "mode-image",
        title = "Image",
        icon = Icons.Default.Image,
    ),
    DrawerItem(
        id = "mode-video",
        title = "Video",
        icon = Icons.Default.Videocam,
    ),
)

@Composable
fun AppDrawerShell(
    modifier: Modifier = Modifier,
    backStack: NavBackStack<NavKey>,
    content: @Composable () -> Unit,
) {
    val drawerController = remember { DrawerController() }
    val modeController = remember { GenerationModeController() }
    val focusModeController = remember { FocusModeController() }

    CompositionLocalProvider(
        LocalDrawerController provides drawerController,
        LocalGenerationModeController provides modeController,
        LocalFocusModeController provides focusModeController,
    ) {
        val currentScreen = backStack.lastOrNull()
        val selectedItemId = when (currentScreen) {
            AppScreen.Home -> "create"
            AppScreen.Search -> "models"
            is AppScreen.Details -> "models"
            AppScreen.Settings -> "settings"
            else -> null
        }
        val selectedModeItemId = when (modeController.mode) {
            GenerationMode.Text -> "mode-text"
            GenerationMode.Image -> "mode-image"
            GenerationMode.Video -> "mode-video"
        }

        val navigateToItem: (DrawerItem) -> Unit = { item ->
            val target = when (item.id) {
                "create" -> AppScreen.Home
                "models" -> AppScreen.Search
                "settings" -> AppScreen.Settings
                else -> null
            }
            if (target != null && currentScreen != target) {
                Snapshot.withMutableSnapshot {
                    backStack.clear()
                    backStack.add(target)
                }
            }
        }
        val selectGenerationMode: (DrawerItem) -> Unit = { item ->
            when (item.id) {
                "mode-text" -> modeController.setState(GenerationMode.Text)
                "mode-image" -> modeController.setState(GenerationMode.Image)
                "mode-video" -> modeController.setState(GenerationMode.Video)
            }
        }

        BoxWithConstraints(modifier = modifier) {
            val appWindowWidth = maxWidth
            val navigation = adaptiveLayoutPolicy(maxWidth, AppContentKind.Chat).navigation
            val effectiveNavigation = if (
                focusModeController.isActive && currentScreen == AppScreen.Home
            ) {
                AppNavigationLayout.ModalSidebar
            } else {
                navigation
            }
            AdaptiveNavigation(
                navigation = effectiveNavigation,
                items = primaryNavigationItems,
                footerItems = utilityNavigationItems,
                selectedItemId = selectedItemId,
                onItemClick = navigateToItem,
                onFooterItemClick = navigateToItem,
                drawerController = drawerController,
                contextualItems = if (
                    effectiveNavigation != AppNavigationLayout.Rail &&
                    currentScreen == AppScreen.Home
                ) {
                    generationModeItems
                } else {
                    emptyList()
                },
                selectedContextualItemId = selectedModeItemId,
                onContextualItemClick = selectGenerationMode,
                content = {
                    CompositionLocalProvider(
                        LocalAppWindowWidth provides appWindowWidth,
                        LocalAppNavigationLayout provides effectiveNavigation,
                        LocalNavigationMenuAction provides if (
                            effectiveNavigation == AppNavigationLayout.ModalSidebar &&
                            currentScreen !is AppScreen.Details
                        ) {
                            drawerController::toggle
                        } else {
                            null
                        },
                    ) {
                        content()
                    }
                },
            )
        }
    }
}
