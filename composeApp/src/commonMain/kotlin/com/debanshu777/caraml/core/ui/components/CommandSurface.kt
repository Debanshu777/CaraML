package com.debanshu777.caraml.core.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.AppTheme

@Composable
fun CommandSurface(
    focused: Boolean,
    active: Boolean,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(AppTheme.spacing.spacing16),
    idleContainerColor: Color? = null,
    idleBorderColor: Color? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = AppTheme.auroraColors
    val emphasized = focused || active
    val shape = AppTheme.shapes.large
    val container = (idleContainerColor ?: colors.commandSurface).let {
        if (AppTheme.softEffects) it.copy(alpha = it.alpha * 0.82f) else it.copy(alpha = 1f)
    }
    // Translucency and a soft shadow are shared across platforms. This does not blur the content behind it.
    Surface(
        modifier = modifier.then(
            if (AppTheme.softEffects) Modifier.shadow(
                elevation = 8.dp,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.05f),
                spotColor = Color.Black.copy(alpha = 0.05f),
            ) else Modifier,
        ),
        shape = shape,
        color = container,
        contentColor = AppTheme.colors.onSurface,
        border = BorderStroke(
            1.dp,
            if (emphasized) AppTheme.colors.primary else idleBorderColor
                ?: lerp(AppTheme.colors.outlineVariant, AppTheme.colors.surface, 0.3f),
        ),
    ) {
        Box(modifier = Modifier.padding(contentPadding), content = content)
    }
}
