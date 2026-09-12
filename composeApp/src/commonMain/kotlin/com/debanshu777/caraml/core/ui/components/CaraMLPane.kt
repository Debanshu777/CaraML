package com.debanshu777.caraml.core.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.AuroraSurfaceLevel
import com.debanshu777.caraml.core.theme.auroraColors

@Composable
fun CaraMLPane(
    modifier: Modifier = Modifier,
    level: AuroraSurfaceLevel = AuroraSurfaceLevel.Pane,
    shape: Shape = MaterialTheme.shapes.medium,
    showBorder: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = level.containerColor(MaterialTheme.colorScheme),
        border = if (showBorder) BorderStroke(1.dp, MaterialTheme.auroraColors.paneBorder) else null,
        shadowElevation = if (level == AuroraSurfaceLevel.Floating) 3.dp else 0.dp,
    ) {
        Column(content = content)
    }
}
