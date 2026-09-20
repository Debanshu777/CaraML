package com.debanshu777.caraml.core.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import com.debanshu777.caraml.core.theme.auroraColors
import com.debanshu777.caraml.core.ui.motion.LocalAuroraMotionPolicy

enum class FocalEntrance {
    None,
    OneShot,
}

/** The single deliberate gradient treatment a destination may use above the ambient backdrop. */
@Composable
fun AuroraFocalSurface(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    entrance: FocalEntrance = FocalEntrance.None,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = MaterialTheme.auroraColors
    val grain = rememberAuroraGrainBrush(colors.grainTint)
    val motion = LocalAuroraMotionPolicy.current
    val startsSettled = entrance == FocalEntrance.None || !motion.spatialTransitionsEnabled
    val reveal = remember(entrance, motion.spatialTransitionsEnabled) {
        Animatable(if (startsSettled) 1f else 0f)
    }
    LaunchedEffect(reveal, startsSettled, motion.focalEntranceMillis) {
        if (startsSettled) {
            reveal.snapTo(1f)
        } else {
            reveal.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = motion.focalEntranceMillis,
                    easing = LinearOutSlowInEasing,
                ),
            )
        }
    }
    val revealProgress = reveal.value
    Box(
        modifier = modifier
            .clip(shape)
            .drawWithCache {
                val primary = Brush.radialGradient(
                    colors = listOf(
                        colors.focusPrimary.copy(alpha = 0.36f * revealProgress),
                        Color.Transparent,
                    ),
                    center = Offset(size.width * -0.06f, size.height * 0.04f),
                    radius = size.maxDimension * 0.92f,
                )
                val secondary = Brush.radialGradient(
                    colors = listOf(
                        colors.secondaryGlow.copy(alpha = 0.38f * revealProgress),
                        Color.Transparent,
                    ),
                    center = Offset(size.width * 0.94f, size.height * 0.18f),
                    radius = size.minDimension * 0.78f,
                )
                val tertiary = Brush.radialGradient(
                    colors = listOf(
                        colors.focusTertiary.copy(alpha = 0.32f * revealProgress),
                        Color.Transparent,
                    ),
                    center = Offset(size.width * 0.72f, size.height * 0.96f),
                    radius = size.minDimension * 0.84f,
                )
                val vignette = Brush.radialGradient(
                    0f to Color.Transparent,
                    0.58f to Color.Transparent,
                    1f to colors.edgeVignette.copy(alpha = colors.edgeVignette.alpha * 0.65f),
                    center = Offset(size.width * 0.5f, size.height * 0.5f),
                    radius = size.maxDimension * 0.62f,
                )
                val grainAlpha = colors.grainTint.alpha
                val grainFilter = ColorFilter.tint(colors.grainTint.copy(alpha = 1f))
                onDrawBehind {
                    drawRect(colors.canvas)
                    drawRect(primary)
                    drawRect(tertiary)
                    drawRect(secondary)
                    drawRect(vignette)
                    drawRect(
                        brush = grain,
                        alpha = grainAlpha,
                        colorFilter = grainFilter,
                    )
                }
            },
        content = content,
    )
}
