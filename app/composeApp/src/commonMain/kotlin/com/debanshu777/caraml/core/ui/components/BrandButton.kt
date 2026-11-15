package com.debanshu777.caraml.core.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.core.theme.AppMotionTokens
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class BrandButtonStyle { Primary, Secondary, Destructive }

/** A raised action with one shared silhouette, stationary hitbox, and policy-aware press feedback. */
@Composable
fun BrandButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: BrandButtonStyle = BrandButtonStyle.Primary,
    contentPadding: PaddingValues = PaddingValues(
        horizontal = AppTheme.buttons.horizontalPadding,
        vertical = AppTheme.buttons.verticalPadding,
    ),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    BrandButtonFrame(onClick, modifier, enabled, style, interactionSource) {
        Row(
            modifier = Modifier.padding(contentPadding),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/** Icon actions use a lighter raised face inside a stable accessible touch target. */
@Composable
fun BrandIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: BrandButtonStyle = BrandButtonStyle.Secondary,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    BrandButtonFrame(onClick, modifier, enabled, style, interactionSource, iconOnly = true) {
        Box(
            modifier = Modifier.padding(AppTheme.buttons.iconPadding),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

@Composable
private fun BrandButtonFrame(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    style: BrandButtonStyle,
    interactionSource: MutableInteractionSource?,
    iconOnly: Boolean = false,
    content: @Composable () -> Unit,
) {
    val tokens = AppTheme.buttons
    val depth = if (iconOnly) tokens.iconDepth else tokens.depth
    val inset = if (iconOnly) tokens.iconVisualInset else 0.dp
    val colors = AppTheme.colors
    val motion = AppTheme.motion
    val source = interactionSource ?: remember { MutableInteractionSource() }
    var pressed by remember(source) { mutableStateOf(false) }
    LaunchedEffect(source, enabled) {
        pressed = false
        if (!enabled) return@LaunchedEffect
        val activePresses = mutableSetOf<PressInteraction.Press>()
        var minimumFeedback: Job? = null
        source.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    activePresses += interaction
                    pressed = true
                    minimumFeedback?.cancel()
                    // Scrollables can emit Press and Release together after a quick tap. Keep
                    // that event visible without delaying the click or intercepting scrolling.
                    minimumFeedback = launch {
                        delay(AppMotionTokens.minimumPressFeedbackMillis)
                        minimumFeedback = null
                        if (activePresses.isEmpty()) pressed = false
                    }
                }
                is PressInteraction.Release -> {
                    activePresses -= interaction.press
                    if (activePresses.isEmpty() && minimumFeedback == null) pressed = false
                }
                is PressInteraction.Cancel -> {
                    activePresses -= interaction.press
                    if (activePresses.isEmpty()) {
                        minimumFeedback?.cancel()
                        minimumFeedback = null
                        pressed = false
                    }
                }
            }
        }
    }
    val focused by source.collectIsFocusedAsState()
    val isPressed = enabled && pressed
    val shape = RoundedCornerShape(if (iconOnly) tokens.iconCornerRadius else tokens.cornerRadius)
    val offset by animateDpAsState(
        targetValue = if (isPressed && motion.spatialTransitionsEnabled) depth else 0.dp,
        animationSpec = if (isPressed) motion.pressInSpec() else motion.pressSpec(),
        label = "brand-button-press",
    )
    val dark = colors.surface.luminance() < 0.5f
    val face = when (style) {
        BrandButtonStyle.Primary -> colors.primary
        BrandButtonStyle.Secondary -> if (dark) colors.surfaceContainerHigh else colors.surface
        BrandButtonStyle.Destructive -> colors.error
    }
    val contentColor = when (style) {
        BrandButtonStyle.Primary -> colors.onPrimary
        BrandButtonStyle.Secondary -> colors.onSurface
        BrandButtonStyle.Destructive -> colors.onError
    }
    // Both button sizes use the same ink base. Dark surfaces need a separate
    // visible rim; lightening the shadow itself makes the raised face look flat.
    val shadow = if (dark) androidx.compose.ui.graphics.Color.Black else AppTheme.brandColors.ink
    val contour = when {
        !enabled -> colors.outlineVariant
        dark -> colors.outlineVariant
        else -> shadow
    }
    val showFeedback = enabled && (isPressed || focused)

    Box(
        modifier = modifier
            .sizeIn(minWidth = tokens.minimumTarget, minHeight = tokens.minimumTarget)
            .clip(shape)
            .clickable(
                interactionSource = source,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = { if (enabled) onClick() },
            ),
        propagateMinConstraints = true,
    ) {
        if (enabled) {
            Box(Modifier.matchParentSize().padding(inset).padding(top = depth).background(shadow, shape))
        }
        Surface(
            modifier = Modifier.padding(inset).padding(bottom = depth).graphicsLayer {
                translationY = if (enabled && motion.spatialTransitionsEnabled) {
                    offset.coerceIn(0.dp, depth).toPx()
                } else {
                    0f
                }
            },
            shape = shape,
            color = if (enabled) face else colors.onSurface.copy(alpha = .08f).compositeOver(colors.surface),
            contentColor = if (enabled) contentColor else colors.onSurface.copy(alpha = .38f),
            border = BorderStroke(
                if (iconOnly) {
                    if (showFeedback) tokens.iconFeedbackContourWidth else tokens.iconContourWidth
                } else if (showFeedback) tokens.feedbackContourWidth else tokens.contourWidth,
                if (showFeedback) contentColor else contour,
            ),
        ) {
            ProvideTextStyle(AppTheme.typography.labelLarge.copy(fontWeight = tokens.labelWeight)) {
                content()
            }
        }
    }
}
