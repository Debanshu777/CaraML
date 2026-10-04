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
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.core.theme.AuroraSurfaceLevel

/**
 * A meaningful tonal group. Prism panes are borderless by default; [showBorder]
 * remains as a source-compatible escape hatch for feature-owned migrations.
 */
@Composable
fun CaraMLPane(
    modifier: Modifier = Modifier,
    level: AuroraSurfaceLevel = AuroraSurfaceLevel.Pane,
    shape: Shape = AppTheme.shapes.medium,
    showBorder: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = level.containerColor(AppTheme.colors).copy(alpha = level.containerAlpha),
        contentColor = AppTheme.colors.onSurface,
        border = if (showBorder) BorderStroke(AppTheme.dimensions.size1, AppTheme.auroraColors.paneBorder) else null,
        shadowElevation = if (level == AuroraSurfaceLevel.Floating) AppTheme.dimensions.size3 else AppTheme.dimensions.size0,
    ) {
        Column(content = content)
    }
}
