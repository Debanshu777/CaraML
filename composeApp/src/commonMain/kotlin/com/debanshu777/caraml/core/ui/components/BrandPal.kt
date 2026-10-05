package com.debanshu777.caraml.core.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.core.ui.motion.LocalAuroraMotionPolicy

/** Presentation states shared by the character and a future authored Rive state machine. */
enum class BrandPalState { Idle, Loading, Thinking, Replying, Success, Error, Paused }

enum class BrandPalAppearance { Write, Imagine, Animate }

/**
 * Offline, multiplatform character. Decorative: callers provide the readable status label.
 * Only state changes animate; historical messages never run an animation loop.
 * This is the Compose renderer, not a Rive runtime or a substitute .riv asset.
 */
@Composable
fun BrandPal(
    state: BrandPalState = BrandPalState.Idle,
    modifier: Modifier = Modifier,
    appearance: BrandPalAppearance = BrandPalAppearance.Write,
) {
    val colors = AppTheme.brandColors
    val motion = LocalAuroraMotionPolicy.current
    val tilt by animateFloatAsState(
        targetValue = if (!motion.spatialTransitionsEnabled) 0f else when (state) {
            BrandPalState.Thinking -> 12f
            BrandPalState.Replying, BrandPalState.Success -> 7f
            BrandPalState.Error, BrandPalState.Paused -> 0f
            BrandPalState.Loading -> -7f
            BrandPalState.Idle -> when (appearance) {
                BrandPalAppearance.Write -> 8f
                BrandPalAppearance.Imagine -> -8f
                BrandPalAppearance.Animate -> 5f
            }
        },
        animationSpec = AppTheme.motion.statusSpec(),
        label = "Pocket pal expression",
    )
    val expression = when (state) {
        BrandPalState.Success -> BrandPalAppearance.Animate
        BrandPalState.Error, BrandPalState.Paused -> BrandPalAppearance.Imagine
        else -> appearance
    }
    val body = when (expression) {
        BrandPalAppearance.Write -> colors.yellow
        BrandPalAppearance.Imagine -> colors.lilac
        BrandPalAppearance.Animate -> colors.mint
    }
    val shade = when (expression) {
        BrandPalAppearance.Write -> Color(0xFFE9B438)
        BrandPalAppearance.Imagine -> Color(0xFFA695EC)
        BrandPalAppearance.Animate -> Color(0xFF85BF95)
    }
    val ink = colors.ink
    Canvas(modifier.size(64.dp).clearAndSetSemantics { }) {
        val unit = minOf(size.width / 80f, size.height / 84f)
        val largeCharacter = size.minDimension >= 48.dp.toPx()
        rotate(tilt) {
            translate((size.width - 80f * unit) / 2f, (size.height - 84f * unit) / 2f) {
                scale(unit, unit, pivot = Offset.Zero) {
                    val corners = when (expression) {
                        BrandPalAppearance.Write -> listOf(0.43f, 0.43f, 0.35f, 0.36f)
                        BrandPalAppearance.Imagine -> listOf(0.32f, 0.43f, 0.35f, 0.39f)
                        BrandPalAppearance.Animate -> List(4) { 0.38f }
                    }.map { CornerRadius(80f * it, 84f * it) }
                    val outline = Path().apply {
                        addRoundRect(RoundRect(
                            left = 0f, top = 0f, right = 80f, bottom = 84f,
                            topLeftCornerRadius = corners[0], topRightCornerRadius = corners[1],
                            bottomRightCornerRadius = corners[2], bottomLeftCornerRadius = corners[3],
                        ))
                    }
                    if (largeCharacter) {
                        translate(top = 4f) { drawPath(outline, Color(0xFF25221E)) }
                        rotate(25f, pivot = Offset(1.5f, 54f)) {
                            drawRoundRect(body, Offset(-5f, 43f), Size(13f, 22f), CornerRadius(10f))
                        }
                        rotate(-25f, pivot = Offset(78.5f, 54f)) {
                            drawRoundRect(body, Offset(72f, 43f), Size(13f, 22f), CornerRadius(10f))
                        }
                    }
                    drawPath(outline, shade)
                    clipPath(outline) {
                        translate(left = -5f, top = -6f) { drawPath(outline, body) }
                    }
                    val squareEyes = expression == BrandPalAppearance.Imagine && state == BrandPalState.Idle
                    val eyeHeight = when {
                        state == BrandPalState.Loading -> 3f
                        squareEyes -> 11f
                        else -> 14f
                    }
                    val eyeWidth = if (squareEyes) 11f else 8f
                    listOf(23f, 48f).forEach { x ->
                        rotate(if (squareEyes) 12f else 0f, pivot = Offset(x + eyeWidth / 2f, 31f)) {
                            drawRoundRect(
                                ink, Offset(x, if (state == BrandPalState.Loading) 31f else 24f),
                                Size(eyeWidth, eyeHeight), CornerRadius(if (squareEyes) 4f else 9f),
                            )
                        }
                    }
                    when {
                        state == BrandPalState.Error || state == BrandPalState.Paused ->
                            drawLine(ink, Offset(35f, 49f), Offset(47f, 49f), 2f, StrokeCap.Round)
                        appearance == BrandPalAppearance.Animate && state == BrandPalState.Idle ->
                            drawOval(ink, Offset(35f, 44f), Size(11f, 11f), style = Stroke(2f))
                        else -> drawArc(
                            color = ink, startAngle = 0f, sweepAngle = 180f, useCenter = false,
                            topLeft = Offset(35f, 42f), size = Size(12f, 10f),
                            style = Stroke(2f, cap = StrokeCap.Round),
                        )
                    }
                }
            }
        }
    }
}
