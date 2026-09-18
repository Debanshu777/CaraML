package com.debanshu777.caraml.core.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import com.debanshu777.caraml.core.theme.auroraColors
import androidx.compose.material3.MaterialTheme

@Composable
fun AuroraBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = MaterialTheme.auroraColors
    val grain = rememberAuroraGrainBrush(colors.grainTint)
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawWithCache {
                val primaryWash = Brush.radialGradient(
                    colors = listOf(
                        colors.primaryGlow.copy(
                            alpha = colors.primaryGlow.alpha * AmbientWashAlphaScale,
                        ),
                        Color.Transparent,
                    ),
                    center = Offset(size.width * -0.08f, size.height * 0.04f),
                    radius = size.maxDimension * 0.72f,
                )
                val secondaryWash = Brush.radialGradient(
                    colors = listOf(
                        colors.secondaryGlow.copy(
                            alpha = colors.secondaryGlow.alpha * AmbientWashAlphaScale,
                        ),
                        Color.Transparent,
                    ),
                    center = Offset(size.width * 0.92f, size.height * 0.20f),
                    radius = size.minDimension * 0.76f,
                )
                val tertiaryWash = Brush.radialGradient(
                    colors = listOf(
                        colors.tertiaryGlow.copy(
                            alpha = colors.tertiaryGlow.alpha * AmbientWashAlphaScale,
                        ),
                        Color.Transparent,
                    ),
                    center = Offset(size.width * 0.72f, size.height * 0.94f),
                    radius = size.minDimension * 0.82f,
                )
                val vignette = Brush.radialGradient(
                    0f to Color.Transparent,
                    0.55f to Color.Transparent,
                    1f to colors.edgeVignette.copy(
                        alpha = colors.edgeVignette.alpha * AmbientVignetteAlphaScale,
                    ),
                    center = Offset(size.width * 0.5f, size.height * 0.5f),
                    radius = size.maxDimension * 0.62f,
                )
                val grainAlpha = colors.grainTint.alpha * AmbientGrainAlphaScale
                val grainFilter = ColorFilter.tint(colors.grainTint.copy(alpha = 1f))
                onDrawBehind {
                    drawRect(color = colors.canvas)
                    drawRect(brush = primaryWash)
                    drawRect(brush = tertiaryWash)
                    drawRect(brush = secondaryWash)
                    drawRect(brush = vignette)
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

private const val GrainTileSize = 64
private const val AmbientWashAlphaScale = 0.25f
private const val AmbientGrainAlphaScale = 0.28f
private const val AmbientVignetteAlphaScale = 0.5f

@Composable
internal fun rememberAuroraGrainBrush(tint: Color): Brush =
    remember(tint.alpha > 0f) { createGrainBrush() }

private fun createGrainBrush(): Brush {
    val bitmap = ImageBitmap(GrainTileSize, GrainTileSize)
    val canvas = Canvas(bitmap)
    val paint = Paint().apply { isAntiAlias = false }

    for (y in 0 until GrainTileSize) {
        for (x in 0 until GrainTileSize) {
            val strength = 0.25f + deterministicGrain(x, y) * 0.75f
            paint.color = Color.White.copy(alpha = strength)
            canvas.drawRect(
                left = x.toFloat(),
                top = y.toFloat(),
                right = x + 1f,
                bottom = y + 1f,
                paint = paint,
            )
        }
    }
    bitmap.prepareToDraw()
    return ShaderBrush(
        ImageShader(
            image = bitmap,
            tileModeX = TileMode.Repeated,
            tileModeY = TileMode.Repeated,
        ),
    )
}

private fun deterministicGrain(x: Int, y: Int): Float {
    var hash = x * 374_761_393 + y * 668_265_263 - 1_640_531_527
    hash = (hash xor (hash ushr 13)) * 1_274_126_177
    hash = hash xor (hash ushr 16)
    return (hash and 0xFF) / 255f
}
