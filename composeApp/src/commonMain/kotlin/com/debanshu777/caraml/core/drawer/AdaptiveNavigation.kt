package com.debanshu777.caraml.core.drawer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.core.theme.AppMotionTokens
import com.debanshu777.caraml.core.theme.AuroraSurfaceLevel
import com.debanshu777.caraml.core.ui.components.CaraMLPane
import com.debanshu777.caraml.core.ui.layout.AppNavigationLayout
import com.debanshu777.caraml.core.ui.motion.AuroraMotionPolicy
import com.debanshu777.caraml.core.ui.motion.LocalAuroraMotionPolicy

@Immutable
internal data class SidebarMotionSpec(
    val panelDurationMillis: Int,
    val scrimDurationMillis: Int,
    val spatialMovementEnabled: Boolean,
)

internal fun sidebarMotionSpec(policy: AuroraMotionPolicy): SidebarMotionSpec {
    val panelDurationMillis = if (policy.spatialTransitionsEnabled) {
        AppMotionTokens.sidebarRevealMillis
    } else {
        AppMotionTokens.reducedOpacityMillis
    }
    return SidebarMotionSpec(
        panelDurationMillis = panelDurationMillis,
        scrimDurationMillis = AppMotionTokens.reducedOpacityMillis,
        spatialMovementEnabled = policy.spatialTransitionsEnabled,
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
    onClose: (() -> Unit)? = null,
    closeFocusRequester: FocusRequester? = null,
    revealProgress: Float = 1f,
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
                .background(Brush.verticalGradient(listOf(
                    AppTheme.brandColors.accent.copy(alpha = if (AppTheme.softEffects) .09f else 0f),
                    Color.Transparent,
                    Color.Transparent,
                )))
                .windowInsetsPadding(contentInsets.only(contentInsetSides)),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (!compact) {
                    Row(
                        modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 38.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        SidebarBrandMark()
                        Text(
                            text = "CaraML",
                            modifier = Modifier.weight(1f),
                            style = AppTheme.typography.headingBase,
                            color = AppTheme.colors.onSurface,
                        )
                        if (onClose != null) {
                            IconButton(
                                onClick = onClose,
                                modifier = Modifier.size(48.dp).then(
                                    closeFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier,
                                ),
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Close navigation menu")
                            }
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.padding(top = AppTheme.spacing.spacing16))
                }

                items.forEachIndexed { index, item ->
                    val itemReveal = (revealProgress * 1.4f - index * 0.12f).coerceIn(0f, 1f)
                    DrawerItemView(
                        item = item,
                        selected = item.id == selectedItemId,
                        showLabel = !compact,
                        onClick = { onItemClick(item) },
                        modifier = Modifier
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                            .graphicsLayer {
                                alpha = itemReveal
                                translationX = -12.dp.toPx() * (1f - itemReveal)
                            },
                    )
                }

                if (!compact && contextualItems.isNotEmpty()) {
                    Text(
                        text = "Create modes",
                        modifier = Modifier.padding(
                            start = AppTheme.dimensions.size20,
                            end = AppTheme.dimensions.size20,
                            top = AppTheme.dimensions.size28,
                            bottom = AppTheme.spacing.spacing8,
                        ),
                        style = AppTheme.typography.labelLarge,
                        color = AppTheme.colors.onSurfaceVariant,
                    )
                    contextualItems.forEach { item ->
                        DrawerItemView(
                            item = item,
                            selected = item.id == selectedContextualItemId,
                            showLabel = true,
                            onClick = { onContextualItemClick(item) },
                            modifier = Modifier.padding(horizontal = AppTheme.spacing.spacing16, vertical = AppTheme.spacing.spacing2),
                        )
                    }
                }
            }

            if (footerItems.isNotEmpty()) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 18.dp),
                    color = AppTheme.colors.outlineVariant,
                )
            }
            footerItems.forEach { item ->
                DrawerItemView(
                    item = item,
                    selected = item.id == selectedItemId,
                    showLabel = !compact,
                    onClick = { onFooterItemClick(item) },
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
            Spacer(modifier = Modifier.padding(bottom = AppTheme.spacing.spacing8))
        }
    }
}

@Composable
private fun SidebarBrandMark() {
    val accent = AppTheme.brandColors.accent
    val ink = AppTheme.brandColors.ink
    Canvas(Modifier.size(30.dp).clearAndSetSemantics { }) {
        drawRoundRect(accent, cornerRadius = CornerRadius(size.width * .33f, size.height * .33f))
        listOf(.32f, .61f).forEach { x ->
            drawRoundRect(ink, Offset(size.width * x, size.height * .31f), Size(size.width * .075f, size.height * .25f), CornerRadius(size.width * .04f))
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
                modifier = Modifier.width(AppTheme.dimensions.size80),
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
                modifier = Modifier.width(AppTheme.dimensions.size256),
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
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    val motion = sidebarMotionSpec(LocalAuroraMotionPolicy.current)
    val reveal by animateFloatAsState(
        targetValue = if (controller.isOpen) 1f else 0f,
        animationSpec = AppTheme.motion.drawerRevealSpec(),
        label = "Sidebar reveal",
    )
    val closeFocusRequester = remember { FocusRequester() }
    val contentFocusRequester = remember { FocusRequester() }
    val layoutDirection = LocalLayoutDirection.current
    val density = LocalDensity.current
    val softEffects = AppTheme.softEffects
    val backState = rememberNavigationEventState(NavigationEventInfo.None)
    val selectAndClose: ((DrawerItem) -> Unit) -> (DrawerItem) -> Unit = { action ->
        { item ->
            action(item)
            controller.close()
        }
    }

    LaunchedEffect(controller.isOpen) {
        if (controller.isOpen) {
            contentFocusRequester.saveFocusedChild()
            closeFocusRequester.requestFocus()
        } else {
            contentFocusRequester.restoreFocusedChild()
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .background(AppTheme.colors.surfaceContainerLow)
            .focusProperties {
                onExit = {
                    if (controller.isOpen) closeFocusRequester.requestFocus()
                }
            }
            .focusGroup()
            .onPreviewKeyEvent { event ->
                if (controller.isOpen && event.type == KeyEventType.KeyUp && event.key == Key.Escape) {
                    controller.close()
                    true
                } else {
                    false
                }
            },
    ) {
        NavigationBackHandler(
            state = backState,
            isBackEnabled = controller.isOpen,
            onBackCompleted = controller::close,
        )

        val panelWidth = (maxWidth * 0.66f).coerceAtMost(320.dp)
        val translation = with(density) { panelWidth.toPx() } *
            if (layoutDirection == LayoutDirection.Ltr) 1f else -1f
        val pageShape = RoundedCornerShape(32.dp * reveal)
        if (controller.isOpen || reveal > 0f) {
            AppNavigationPanel(
                items = items,
                footerItems = footerItems,
                selectedItemId = selectedItemId,
                compact = false,
                onItemClick = selectAndClose(onItemClick),
                onFooterItemClick = selectAndClose(onFooterItemClick),
                modifier = Modifier
                    .width(panelWidth)
                    .align(Alignment.CenterStart)
                    .testTag("modal-sidebar-panel")
                    .focusProperties {
                        onEnter = { if (!controller.isOpen) cancelFocusChange() }
                    }
                    .focusGroup()
                    .then(
                        if (controller.isOpen) {
                            Modifier.semantics {
                                paneTitle = "Navigation menu"
                                dismiss { controller.close(); true }
                            }
                        } else {
                            Modifier.clearAndSetSemantics { }
                        },
                    ),
                contentInsets = navigationInsets,
                contentInsetSides = WindowInsetsSides.Vertical + WindowInsetsSides.Start,
                contextualItems = contextualItems,
                selectedContextualItemId = selectedContextualItemId,
                onContextualItemClick = selectAndClose(onContextualItemClick),
                shape = RectangleShape,
                onClose = controller::close,
                closeFocusRequester = closeFocusRequester,
                revealProgress = if (motion.spatialMovementEnabled) reveal else 1f,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = translation * reveal
                    scaleX = 1f - 0.2f * reveal
                    scaleY = 1f - 0.2f * reveal
                    transformOrigin = TransformOrigin(
                        pivotFractionX = if (layoutDirection == LayoutDirection.Ltr) 0f else 1f,
                        pivotFractionY = 0.5f,
                    )
                    shape = pageShape
                    clip = reveal > 0f
                    shadowElevation = if (softEffects) 24.dp.toPx() * reveal else 0f
                }
                .testTag("sidebar-page-surface")
                .background(AppTheme.colors.background),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .blur(if (softEffects) 2.dp * reveal else 0.dp)
                    .focusRequester(contentFocusRequester)
                    .focusProperties {
                        onEnter = { if (controller.isOpen) cancelFocusChange() }
                    }
                    .focusGroup()
                    .then(if (controller.isOpen) Modifier.clearAndSetSemantics { } else Modifier),
            ) {
                content()
            }
            if (controller.isOpen || reveal > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("modal-sidebar-scrim")
                        .background(AppTheme.colors.scrim.copy(alpha = 0.12f * reveal))
                        .clickable(
                            enabled = controller.isOpen,
                            role = Role.Button,
                            onClickLabel = "Dismiss navigation menu",
                            onClick = controller::close,
                        )
                        .then(
                            if (controller.isOpen) {
                                Modifier.semantics(mergeDescendants = true) {
                                    contentDescription = "Dismiss navigation menu"
                                }
                            } else {
                                Modifier.clearAndSetSemantics { }
                            },
                        ),
                )
            }
        }
    }
}
