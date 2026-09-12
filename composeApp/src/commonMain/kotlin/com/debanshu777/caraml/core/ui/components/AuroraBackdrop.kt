package com.debanshu777.caraml.core.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.debanshu777.caraml.core.theme.auroraColors
import androidx.compose.material3.MaterialTheme

@Composable
fun AuroraBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = MaterialTheme.auroraColors
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawWithCache {
                val primaryWash = Brush.radialGradient(
                    colors = listOf(colors.primaryGlow, Color.Transparent),
                    center = Offset.Zero,
                    radius = size.maxDimension * 0.85f,
                )
                val tertiaryWash = Brush.radialGradient(
                    colors = listOf(colors.tertiaryGlow, Color.Transparent),
                    center = Offset(size.width, size.height),
                    radius = size.maxDimension * 0.65f,
                )
                onDrawBehind {
                    drawRect(color = colors.canvas)
                    drawRect(brush = primaryWash)
                    drawRect(brush = tertiaryWash)
                }
            },
        content = content,
    )
}
