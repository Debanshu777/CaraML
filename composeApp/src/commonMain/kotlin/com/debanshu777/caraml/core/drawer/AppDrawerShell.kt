package com.debanshu777.caraml.core.drawer

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import com.debanshu777.caraml.features.chat.domain.GenerationMode
import com.debanshu777.caraml.core.ui.icons.AppIcons
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

/** Full app-window width before persistent navigation consumes horizontal space. */
internal val LocalAppWindowWidth = compositionLocalOf<Dp?> { null }

private val generationDestinations = mapOf(
    "chat" to GenerationMode.Text,
    "images" to GenerationMode.Image,
    "videos" to GenerationMode.Video,
)

private val generationModeSaver = Saver<GenerationModeController, String>(
    save = { it.mode.name },
    restore = { name -> GenerationModeController(GenerationMode.entries.firstOrNull { it.name == name } ?: GenerationMode.Text) },
)

private val primaryNavigationItems = listOf(
    DrawerItem(id = "chat", title = "Chat", icon = AppIcons.Chat),
    DrawerItem(id = "images", title = "Images", icon = AppIcons.Image),
    DrawerItem(id = "videos", title = "Videos", icon = AppIcons.Video),
    DrawerItem(id = "models", title = "Models", icon = BrandNavigationIcons.Models),
)

private val utilityNavigationItems = listOf(
    DrawerItem(
        id = "settings",
        title = "Settings",
        icon = BrandNavigationIcons.Settings,
    ),
)

@Composable
fun AppDrawerShell(
    modifier: Modifier = Modifier,
    backStack: NavBackStack<NavKey>,
    content: @Composable () -> Unit,
) {
    val drawerController = remember { DrawerController() }
    val modeController = rememberSaveable(saver = generationModeSaver) { GenerationModeController() }
    val focusModeController = remember { FocusModeController() }

    CompositionLocalProvider(
        LocalDrawerController provides drawerController,
        LocalGenerationModeController provides modeController,
        LocalFocusModeController provides focusModeController,
    ) {
        val currentScreen = backStack.lastOrNull()
        val selectedItemId = when (currentScreen) {
            AppScreen.Home -> generationDestinations.entries.first { it.value == modeController.mode }.key
            AppScreen.Search -> "models"
            is AppScreen.Details -> "models"
            AppScreen.Settings -> "settings"
            else -> null
        }
        val navigateToItem: (DrawerItem) -> Unit = { item ->
            val requestedMode = generationDestinations[item.id]
            val target = if (requestedMode != null) AppScreen.Home else when (item.id) {
                "models" -> AppScreen.Search
                "settings" -> AppScreen.Settings
                else -> null
            }
            if (target != null) {
                Snapshot.withMutableSnapshot {
                    requestedMode?.let(modeController::setState)
                    if (currentScreen != target) {
                        backStack.clear()
                        backStack.add(target)
                    }
                }
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
