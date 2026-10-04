package com.debanshu777.caraml.core.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    val shape = AppTheme.shapes.medium
    val outerTreatment = if (emphasized) {
        Modifier.background(color = colors.focusPrimary.copy(alpha = AppTheme.effects.commandFocus), shape = shape)
    } else {
        Modifier.background(color = idleContainerColor ?: colors.commandSurface, shape = shape)
    }

    Box(
        modifier = modifier
            .then(outerTreatment)
            .padding(AppTheme.spacing.spacing2),
        propagateMinConstraints = true,
    ) {
        Surface(
            shape = shape,
            color = idleContainerColor ?: colors.commandSurface,
            contentColor = AppTheme.colors.onSurface,
            border = when {
                emphasized -> BorderStroke(AppTheme.dimensions.size1, colors.focusPrimary)
                idleBorderColor != null -> BorderStroke(AppTheme.dimensions.size1, idleBorderColor)
                else -> null
            },
        ) {
            Box(
                modifier = Modifier.padding(contentPadding),
                content = content,
            )
        }
    }
}
