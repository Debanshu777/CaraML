package com.debanshu777.caraml.core.drawer

import androidx.compose.runtime.getValue
import com.debanshu777.caraml.core.ui.components.BrandIconButton
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
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.withTransform
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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.TextOverflow
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
    navigationItemsWidth: Dp? = null,
    navigationHeaderWidth: Dp? = null,
) {
    val modal = onClose != null
    val accent = AppTheme.brandColors.accent
    val panelBackground = if (modal) {
        Modifier.background(AppTheme.colors.background).drawWithCache {
            val glow = Brush.radialGradient(
                0f to accent.copy(alpha = .24f),
                .65f to Color.Transparent,
                1f to Color.Transparent,
                center = Offset.Zero,
                radius = (size.height * 1.414214f).coerceAtLeast(1f),
            )
            onDrawBehind {
                // CSS's corner ellipse scales with both dimensions of the full sidebar.
                withTransform({ scale(size.width / size.height.coerceAtLeast(1f), 1f, Offset.Zero) }) {
                    drawRect(glow, size = Size(size.height, size.height))
                }
            }
        }
    } else {
        Modifier.background(Brush.verticalGradient(listOf(
            accent.copy(alpha = if (AppTheme.softEffects) .09f else 0f),
            Color.Transparent,
            Color.Transparent,
        )))
    }
    CaraMLPane(
        modifier = modifier.fillMaxHeight(),
        level = surfaceLevel,
        shape = shape,
        showBorder = false,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(panelBackground)
                .windowInsetsPadding(contentInsets.only(contentInsetSides)),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (!compact) {
                    Row(
                        modifier = Modifier
                            .then(navigationHeaderWidth?.let { Modifier.width(it) } ?: Modifier.fillMaxWidth())
                            .padding(
                            start = if (modal) 19.dp else 16.dp,
                            end = if (modal) 19.dp else 8.dp,
                            top = if (modal) 17.dp else 16.dp,
                            bottom = if (modal) 52.dp else 38.dp,
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        SidebarBrandMark()
                        Text(
                            text = "CaraML",
                            modifier = Modifier.weight(1f),
                            style = AppTheme.typography.sidebarWordmark25,
                            color = AppTheme.colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (onClose != null) {
                            // Keep the reference's 44dp layout and a native 48dp touch target.
                            Box(Modifier.size(44.dp)) {
                                BrandIconButton(
                                    onClick = onClose,
                                    modifier = Modifier.requiredSize(48.dp).align(Alignment.Center)
                                        .then(closeFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                                        .semantics { contentDescription = "Close navigation menu" },
                                ) {
                                    Icon(BrandNavigationIcons.Close, contentDescription = null, modifier = Modifier.size(22.dp))
                                }
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
                            .padding(
                                start = if (modal) 19.dp else 14.dp,
                                end = if (modal) 0.dp else 14.dp,
                                top = if (modal) 0.dp else 6.dp,
                                bottom = if (modal) 12.dp else 6.dp,
                            )
                            .then(navigationItemsWidth?.let { Modifier.width(it) } ?: Modifier)
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
                    modifier = Modifier.padding(
                        start = if (modal) 19.dp else 16.dp,
                        end = if (modal) 0.dp else 16.dp,
                        top = if (modal) 0.dp else 12.dp,
                        bottom = 18.dp,
                    ).then(navigationItemsWidth?.let { Modifier.width(it) } ?: Modifier),
                    color = AppTheme.colors.outlineVariant,
                )
            }
            footerItems.forEach { item ->
                DrawerItemView(
                    item = item,
                    selected = item.id == selectedItemId,
                    showLabel = !compact,
                    onClick = { onFooterItemClick(item) },
                    modifier = Modifier.padding(
                        start = if (modal) 19.dp else 14.dp,
                        end = if (modal) 0.dp else 14.dp,
                        top = if (modal) 0.dp else 6.dp,
                        bottom = if (modal) 0.dp else 6.dp,
                    ).then(navigationItemsWidth?.let { Modifier.width(it) } ?: Modifier),
                )
            }
            Spacer(modifier = Modifier.height(if (modal) 30.dp else AppTheme.spacing.spacing8))
        }
    }
}

@Composable
private fun SidebarBrandMark() {
    val accent = AppTheme.brandColors.accent
    val ink = AppTheme.brandColors.ink
    val accentLuminance = accent.luminance()
    val inkLuminance = ink.luminance()
    val inkContrast = (maxOf(accentLuminance, inkLuminance) + .05f) / (minOf(accentLuminance, inkLuminance) + .05f)
    val eyeColor = if (inkContrast >= 1.05f / (accentLuminance + .05f)) ink else Color.White
    Canvas(Modifier.size(25.dp, 26.dp).graphicsLayer { rotationZ = -8f }.clearAndSetSemantics { }) {
        val body = Path().apply {
            addRoundRect(RoundRect(
                left = 0f, top = 0f, right = size.width, bottom = size.height,
                topLeftCornerRadius = CornerRadius(8.dp.toPx()),
                topRightCornerRadius = CornerRadius(10.dp.toPx()),
                bottomRightCornerRadius = CornerRadius(10.dp.toPx()),
                bottomLeftCornerRadius = CornerRadius(4.dp.toPx()),
            ))
        }
        drawPath(body, accent)
        listOf(7.dp, 15.dp).forEach { x ->
            drawRoundRect(eyeColor, Offset(x.toPx(), 9.dp.toPx()), Size(4.dp.toPx(), 7.dp.toPx()), CornerRadius(4.dp.toPx()))
        }
    }
}

/** Scale and round the entire page without cutting into its header or bottom controls. */
private class SidebarPageShape(
    private val reveal: Float,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val radius = with(density) { 31.dp.toPx() * reveal }
        return Outline.Rounded(RoundRect(Rect(0f, 0f, size.width, size.height), CornerRadius(radius)))
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
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val leadingInsets = navigationInsets.only(WindowInsetsSides.Start)
    // Extend the painted panel into the safe area without shifting the whole window.
    val leadingInset = with(density) {
        (leadingInsets.getLeft(density, layoutDirection) +
            leadingInsets.getRight(density, layoutDirection)).toDp()
    }
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
            modifier = modifier.fillMaxSize(),
        ) {
            AppNavigationPanel(
                items = items,
                footerItems = footerItems,
                selectedItemId = selectedItemId,
                compact = true,
                onItemClick = onItemClick,
                onFooterItemClick = onFooterItemClick,
                modifier = Modifier.width(AppTheme.dimensions.size80 + leadingInset),
                contentInsets = navigationInsets,
                contentInsetSides = WindowInsetsSides.Vertical + WindowInsetsSides.Start,
            )
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f)
                    .consumeWindowInsets(leadingInsets),
            ) {
                content()
            }
        }

        AppNavigationLayout.Sidebar -> Row(
            modifier = modifier.fillMaxSize(),
        ) {
            AppNavigationPanel(
                items = items,
                footerItems = footerItems,
                selectedItemId = selectedItemId,
                compact = false,
                onItemClick = onItemClick,
                onFooterItemClick = onFooterItemClick,
                modifier = Modifier.width(AppTheme.dimensions.size256 + leadingInset),
                contentInsets = navigationInsets,
                contentInsetSides = WindowInsetsSides.Vertical + WindowInsetsSides.Start,
                contextualItems = contextualItems,
                selectedContextualItemId = selectedContextualItemId,
                onContextualItemClick = onContextualItemClick,
            )
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f)
                    .consumeWindowInsets(leadingInsets),
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

        val topInset = navigationInsets.getTop(density).toFloat()
        val bottomInset = navigationInsets.getBottom(density).toFloat()
        val safeWidth = maxWidth - with(density) {
            (navigationInsets.getLeft(density, layoutDirection) + navigationInsets.getRight(density, layoutDirection)).toDp()
        }
        val navigationItemsWidth = ((safeWidth - 38.dp) * .66f - 10.dp).coerceAtLeast(0.dp)
        val translation = with(density) { (maxWidth * .66f).toPx() } *
            if (layoutDirection == LayoutDirection.Ltr) 1f else -1f
        val pageShape = SidebarPageShape(reveal)
        val wordmarkLineHeight = AppTheme.typography.sidebarWordmark25.lineHeight
        val headerClearance = with(density) {
            17.dp.toPx() + maxOf(44.dp.toPx(), wordmarkLineHeight.toPx()) / 2f + 24.dp.toPx() + 8.dp.toPx()
        }
        val fullHeight = with(density) { maxHeight.toPx() }
        val safeHeight = (fullHeight - topInset - bottomInset).coerceAtLeast(0f)
        val openPageScale = if (fullHeight > 0f) .8f * safeHeight / fullHeight else .8f
        // Short windows cannot expose the full header above the preview. Keep its close
        // action inside the revealed leading area, without changing the portrait layout.
        val navigationHeaderWidth = if (safeHeight * .1f < headerClearance) {
            minOf(safeWidth, maxWidth * .66f)
        } else null
        if (controller.isOpen || reveal > 0f) {
            AppNavigationPanel(
                items = items,
                footerItems = footerItems,
                selectedItemId = selectedItemId,
                compact = false,
                onItemClick = selectAndClose(onItemClick),
                onFooterItemClick = selectAndClose(onFooterItemClick),
                modifier = Modifier
                    .fillMaxWidth()
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
                contentInsetSides = WindowInsetsSides.Vertical + WindowInsetsSides.Horizontal,
                contextualItems = contextualItems,
                selectedContextualItemId = selectedContextualItemId,
                onContextualItemClick = selectAndClose(onContextualItemClick),
                shape = RectangleShape,
                onClose = controller::close,
                closeFocusRequester = closeFocusRequester,
                revealProgress = if (motion.spatialMovementEnabled) reveal else 1f,
                navigationItemsWidth = navigationItemsWidth,
                navigationHeaderWidth = navigationHeaderWidth,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = translation * reveal
                    // Preserve a single coordinate system for the page, its chrome, and safe insets.
                    translationY = (topInset - bottomInset) * .5f * reveal
                    scaleX = 1f - (1f - openPageScale) * reveal
                    scaleY = 1f - (1f - openPageScale) * reveal
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
                    .blur(if (softEffects) 1.4.dp * reveal else 0.dp)
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
