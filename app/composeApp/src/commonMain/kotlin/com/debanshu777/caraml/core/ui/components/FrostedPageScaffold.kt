package com.debanshu777.caraml.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.offset
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.core.ui.layout.AppContentKind
import com.debanshu777.caraml.core.ui.layout.ResponsiveContentPane
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeSourceRetention
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import kotlin.math.roundToInt

/** Scroll content fills the window behind the glass. Insets belong inside each scroller,
 * keeping its first and last items clear of the fixed controls at rest. */
@Composable
fun FrostedPageScaffold(
    kind: AppContentKind,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)?,
    safeInsets: WindowInsets = WindowInsets.safeDrawing,
    scrollableHeader: Boolean = false,
    bottomBar: (@Composable () -> Unit)? = null,
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val haze = rememberHazeState()
    val surface = AppTheme.colors.background
    val effects = AppTheme.effects
    val glass = AppTheme.softEffects
    val style = HazeBlurStyle {
        backgroundColor(surface)
        blurRadius(effects.chromeBlur)
        noiseFactor(0f)
        colorEffects(listOf(HazeColorEffect.tint(surface.copy(alpha = effects.chromeTint))))
        fallbackColorEffect(HazeColorEffect.tint(surface))
    }
    val chrome = if (glass) Modifier else Modifier.background(surface)
    // Blank chrome must also own its hit area; blurred rows behind it are not tap targets.
    val chromeHitArea = Modifier.pointerInput(Unit) { detectTapGestures { } }
    BoxWithConstraints(modifier.fillMaxSize().imePadding()) {
        val maxHeaderHeight = maxHeight * .45f
        val headerOverflow = scrollableHeader && maxHeight < 480.dp
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = snackbarHost,
            topBar = {
                Box(Modifier.fillMaxWidth().then(chrome).then(chromeHitArea).testTag("page-sticky-chrome")) {
                    if (glass) ChromeBackdrop(haze, style, fadeAtBottom = true)
                    if (header == null) {
                        // Scrolling page controls still need glass behind the status bar.
                        Spacer(Modifier.fillMaxWidth().windowInsetsTopHeight(safeInsets))
                    } else {
                        ResponsiveContentPane(
                            kind = kind, fillMaxHeight = false,
                            modifier = Modifier.windowInsetsPadding(safeInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
                        ) {
                            Column(Modifier.fillMaxWidth().then(
                                if (headerOverflow) Modifier.heightIn(max = maxHeaderHeight)
                                    .verticalScroll(rememberScrollState()) else Modifier,
                            ).padding(vertical = AppTheme.spacing.spacing12)) { header() }
                        }
                    }
                }
            },
            bottomBar = {
                Box(Modifier.fillMaxWidth().then(chrome).then(chromeHitArea)) {
                    if (glass) ChromeBackdrop(haze, style, fadeAtBottom = false)
                    Column(Modifier.fillMaxWidth()) {
                        if (bottomBar != null) ResponsiveContentPane(kind = kind, fillMaxHeight = false, modifier = Modifier.windowInsetsPadding(safeInsets.only(WindowInsetsSides.Horizontal))) { bottomBar() }
                        Spacer(Modifier.fillMaxWidth().windowInsetsBottomHeight(safeInsets))
                    }
                }
            },
        ) { padding ->
            // Draw through the gesture inset so its glass samples the same continuous
            // content. Reserve the chrome and its fade inside the scroller, allowing
            // the final action to settle completely above both at the end of the list.
            val topChrome = padding.calculateTopPadding()
            val topFade = if (header == null && glass && topChrome > 0.dp) effects.chromeFade else 0.dp
            val bottomChrome = padding.calculateBottomPadding()
            val bottomFade = if (glass && bottomChrome > 0.dp) effects.chromeFade else 0.dp
            AuroraBackdrop(Modifier.then(if (glass) Modifier.hazeSource(haze) else Modifier)) {
                ResponsiveContentPane(kind = kind,
                    modifier = Modifier.consumeWindowInsets(padding)
                        .windowInsetsPadding(safeInsets.only(WindowInsetsSides.Horizontal)),
                ) {
                    content(PaddingValues(
                        top = topChrome + topFade,
                        bottom = bottomChrome + bottomFade,
                    ))
                }
            }
        }
    }
}

/** One continuous backdrop extends beyond the controls; only its opacity fades.
 * The extension takes no layout space and does not intercept touches on visible rows. */
@Composable
private fun BoxScope.ChromeBackdrop(haze: HazeState, style: HazeBlurStyle, fadeAtBottom: Boolean) {
    val fadePx = with(LocalDensity.current) { AppTheme.effects.chromeFade.toPx() }
    val edgeMask = remember(fadePx, fadeAtBottom) {
        object : ShaderBrush() {
            override fun createShader(size: Size): Shader {
                val start = if (fadeAtBottom) size.height - fadePx else 0f
                val colors = listOf(1f, .844f, .5f, .156f, 0f)
                    .let { if (fadeAtBottom) it else it.reversed() }
                    .map { Color.Black.copy(alpha = it) }
                return LinearGradientShader(
                    from = Offset(0f, start),
                    to = Offset(0f, start + fadePx),
                    colors = colors,
                )
            }
        }
    }
    Spacer(
        Modifier.matchParentSize()
            .layout { measurable, constraints ->
                val extension = if (constraints.maxHeight > 0) fadePx.roundToInt() else 0
                val placeable = measurable.measure(constraints.offset(vertical = extension))
                layout(placeable.width, placeable.height - extension) {
                    placeable.place(0, if (fadeAtBottom) 0 else -extension)
                }
            }
            .hazeBlur(
                input = HazeInput.Sources(haze, retention = HazeSourceRetention.ClearWhenUnavailable),
                style = style.then { mask(edgeMask) },
            ),
    )
}
