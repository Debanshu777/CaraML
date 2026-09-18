package com.debanshu777.caraml.core.drawer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.debanshu777.caraml.core.theme.AuroraSurfaceLevel
import com.debanshu777.caraml.core.ui.components.CaraMLPane
import com.debanshu777.caraml.core.ui.layout.AppNavigationLayout
import com.debanshu777.caraml.core.ui.motion.AuroraMotionPolicy
import com.debanshu777.caraml.core.ui.motion.LocalAuroraMotionPolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Immutable
internal data class SidebarMotionSpec(
    val panelDurationMillis: Int,
    val scrimDurationMillis: Int,
    val spatialMovementEnabled: Boolean,
    val selectionDelayMillis: Int,
)

internal fun sidebarMotionSpec(policy: AuroraMotionPolicy): SidebarMotionSpec {
    val panelDurationMillis = if (policy.spatialTransitionsEnabled) 180 else 90
    val scaledSelectionDelay = if (policy.spatialTransitionsEnabled) {
        (panelDurationMillis * policy.durationScale).roundToInt().coerceAtLeast(1)
    } else {
        panelDurationMillis
    }
    return SidebarMotionSpec(
        panelDurationMillis = panelDurationMillis,
        scrimDurationMillis = 90,
        spatialMovementEnabled = policy.spatialTransitionsEnabled,
        selectionDelayMillis = scaledSelectionDelay,
    )
}

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
    footerItems: List<DrawerItem> = emptyList(),
    onFooterItemClick: (DrawerItem) -> Unit = onItemClick,
    shape: Shape = RectangleShape,
) {
    CaraMLPane(
        modifier = modifier.fillMaxHeight(),
        level = surfaceLevel,
        shape = shape,
        showBorder = false,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(contentInsets.only(contentInsetSides)),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
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
                    Spacer(modifier = Modifier.padding(top = 16.dp))
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
                        modifier = Modifier.padding(
                            start = 20.dp,
                            end = 20.dp,
                            top = 28.dp,
                            bottom = 8.dp,
                        ),
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

            footerItems.forEach { item ->
                DrawerItemView(
                    item = item,
                    selected = item.id == selectedItemId,
                    showLabel = !compact,
                    onClick = { onFooterItemClick(item) },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            Spacer(modifier = Modifier.padding(bottom = 8.dp))
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
    footerItems: List<DrawerItem> = emptyList(),
    onFooterItemClick: (DrawerItem) -> Unit = onItemClick,
    drawerController: DrawerController? = null,
    content: @Composable () -> Unit,
) {
    val effectiveDrawerController = drawerController ?: remember { DrawerController() }
    val shellScope = rememberCoroutineScope()

    LaunchedEffect(navigation) {
        if (navigation != AppNavigationLayout.ModalSidebar) {
            effectiveDrawerController.close()
        }
    }

    when (navigation) {
        AppNavigationLayout.ModalSidebar -> ModalSidebarNavigation(
            controller = effectiveDrawerController,
            items = items,
            footerItems = footerItems,
            selectedItemId = selectedItemId,
            onItemClick = onItemClick,
            onFooterItemClick = onFooterItemClick,
            navigationInsets = navigationInsets,
            contextualItems = contextualItems,
            selectedContextualItemId = selectedContextualItemId,
            onContextualItemClick = onContextualItemClick,
            shellScope = shellScope,
            modifier = modifier,
            content = content,
        )

        AppNavigationLayout.Rail -> Row(
            modifier = modifier
                .fillMaxSize()
                .windowInsetsPadding(navigationInsets.only(WindowInsetsSides.Start)),
        ) {
            AppNavigationPanel(
                items = items,
                footerItems = footerItems,
                selectedItemId = selectedItemId,
                compact = true,
                onItemClick = onItemClick,
                onFooterItemClick = onFooterItemClick,
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

        AppNavigationLayout.Sidebar -> Row(
            modifier = modifier
                .fillMaxSize()
                .windowInsetsPadding(navigationInsets.only(WindowInsetsSides.Start)),
        ) {
            AppNavigationPanel(
                items = items,
                footerItems = footerItems,
                selectedItemId = selectedItemId,
                compact = false,
                onItemClick = onItemClick,
                onFooterItemClick = onFooterItemClick,
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
private fun ModalSidebarNavigation(
    controller: DrawerController,
    items: List<DrawerItem>,
    footerItems: List<DrawerItem>,
    selectedItemId: String?,
    onItemClick: (DrawerItem) -> Unit,
    onFooterItemClick: (DrawerItem) -> Unit,
    navigationInsets: WindowInsets,
    contextualItems: List<DrawerItem>,
    selectedContextualItemId: String?,
    onContextualItemClick: (DrawerItem) -> Unit,
    shellScope: kotlinx.coroutines.CoroutineScope,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    val motion = sidebarMotionSpec(LocalAuroraMotionPolicy.current)
    val backState = rememberNavigationEventState(NavigationEventInfo.None)
    val closeThen: ((DrawerItem) -> Unit) -> (DrawerItem) -> Unit = { action ->
        { item ->
            controller.close()
            shellScope.launch {
                delay(motion.selectionDelayMillis.toLong())
                action(item)
            }
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusProperties { canFocus = !controller.isOpen }
                .then(
                    if (controller.isOpen) {
                        Modifier.clearAndSetSemantics { }
                    } else {
                        Modifier
                    },
                ),
        ) {
            content()
        }

        NavigationBackHandler(
            state = backState,
            isBackEnabled = controller.isOpen,
            onBackCompleted = controller::close,
        )

        AnimatedVisibility(
            visible = controller.isOpen,
            modifier = Modifier
                .fillMaxSize()
                .testTag("modal-sidebar-scrim"),
            enter = fadeIn(tween(motion.scrimDurationMillis)),
            exit = fadeOut(tween(motion.scrimDurationMillis)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.44f))
                    .clickable(
                        role = Role.Button,
                        onClickLabel = "Dismiss navigation menu",
                        onClick = controller::close,
                    )
                    .semantics(mergeDescendants = true) {
                        contentDescription = "Dismiss navigation menu"
                    },
            )
        }

        val panelWidth = (maxWidth - 40.dp).coerceAtMost(320.dp).coerceAtLeast(0.dp)
        val panelEnter = if (motion.spatialMovementEnabled) {
            fadeIn(tween(motion.scrimDurationMillis)) +
                slideInHorizontally(tween(motion.panelDurationMillis)) { -it }
        } else {
            fadeIn(tween(motion.panelDurationMillis))
        }
        val panelExit = if (motion.spatialMovementEnabled) {
            fadeOut(tween(motion.scrimDurationMillis)) +
                slideOutHorizontally(tween(motion.panelDurationMillis)) { -it }
        } else {
            fadeOut(tween(motion.panelDurationMillis))
        }

        AnimatedVisibility(
            visible = controller.isOpen,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .testTag("modal-sidebar-panel"),
            enter = panelEnter,
            exit = panelExit,
        ) {
            AppNavigationPanel(
                items = items,
                footerItems = footerItems,
                selectedItemId = selectedItemId,
                compact = false,
                onItemClick = closeThen(onItemClick),
                onFooterItemClick = closeThen(onFooterItemClick),
                modifier = Modifier.width(panelWidth),
                contentInsets = navigationInsets,
                contentInsetSides = WindowInsetsSides.Vertical + WindowInsetsSides.Start,
                contextualItems = contextualItems,
                selectedContextualItemId = selectedContextualItemId,
                onContextualItemClick = closeThen(onContextualItemClick),
                shape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
            )
        }
    }
}
