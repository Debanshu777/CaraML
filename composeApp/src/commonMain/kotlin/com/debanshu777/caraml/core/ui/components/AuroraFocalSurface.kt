package com.debanshu777.caraml.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import com.debanshu777.caraml.core.theme.auroraColors

/** The single deliberate gradient treatment a destination may use above the ambient backdrop. */
@Composable
fun AuroraFocalSurface(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = MaterialTheme.auroraColors
    Box(
        modifier = modifier.background(
            brush = Brush.linearGradient(
                colors = listOf(colors.primaryGlow, colors.tertiaryGlow),
            ),
            shape = shape,
        ),
        content = content,
    )
}
