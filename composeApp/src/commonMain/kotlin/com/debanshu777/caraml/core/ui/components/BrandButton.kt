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

/** Icon actions use the same face and depth while retaining the caller's accessible description. */
@Composable
fun BrandIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: BrandButtonStyle = BrandButtonStyle.Secondary,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    BrandButtonFrame(onClick, modifier, enabled, style, interactionSource) {
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
    content: @Composable () -> Unit,
) {
    val tokens = AppTheme.buttons
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
    val shape = RoundedCornerShape(tokens.cornerRadius)
    val offset by animateDpAsState(
        targetValue = if (isPressed && motion.spatialTransitionsEnabled) tokens.depth else 0.dp,
        animationSpec = if (isPressed) motion.pressInSpec() else motion.pressSpec(),
        label = "brand-button-press",
    )
    val face = when (style) {
        BrandButtonStyle.Primary -> colors.primary
        BrandButtonStyle.Secondary -> colors.surface
        BrandButtonStyle.Destructive -> colors.error
    }
    val contentColor = when (style) {
        BrandButtonStyle.Primary -> colors.onPrimary
        BrandButtonStyle.Secondary -> colors.onSurface
        BrandButtonStyle.Destructive -> colors.onError
    }
    val contour = if (enabled) AppTheme.brandColors.ink else colors.outlineVariant
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
            Box(Modifier.matchParentSize().padding(top = tokens.depth).background(contour, shape))
        }
        Surface(
            modifier = Modifier.padding(bottom = tokens.depth).graphicsLayer {
                translationY = if (enabled && motion.spatialTransitionsEnabled) {
                    offset.coerceIn(0.dp, tokens.depth).toPx()
                } else {
                    0f
                }
            },
            shape = shape,
            color = if (enabled) face else colors.onSurface.copy(alpha = .08f).compositeOver(colors.surface),
            contentColor = if (enabled) contentColor else colors.onSurface.copy(alpha = .38f),
            border = BorderStroke(
                if (showFeedback) tokens.feedbackContourWidth else tokens.contourWidth,
                if (showFeedback) contentColor else contour,
            ),
        ) {
            ProvideTextStyle(AppTheme.typography.labelLarge.copy(fontWeight = tokens.labelWeight)) {
                content()
            }
        }
    }
}
