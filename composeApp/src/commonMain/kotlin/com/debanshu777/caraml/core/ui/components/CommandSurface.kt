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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.auroraColors

@Composable
fun CommandSurface(
    focused: Boolean,
    active: Boolean,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = MaterialTheme.auroraColors
    val emphasized = focused || active
    val shape = MaterialTheme.shapes.medium
    val focusBoundary = remember(colors.focusPrimary, colors.focusTertiary) {
        Brush.horizontalGradient(listOf(colors.focusPrimary, colors.focusTertiary))
    }
    val outerGlow = remember(colors.focusPrimary, colors.focusTertiary) {
        Brush.horizontalGradient(
            listOf(
                colors.focusPrimary.copy(alpha = 0.22f),
                colors.focusTertiary.copy(alpha = 0.18f),
            ),
        )
    }
    val outerTreatment = if (emphasized) {
        Modifier.background(brush = outerGlow, shape = shape)
    } else {
        Modifier.background(color = colors.commandSurface, shape = shape)
    }

    Box(
        modifier = modifier
            .then(outerTreatment)
            .padding(2.dp),
        propagateMinConstraints = true,
    ) {
        Surface(
            shape = shape,
            color = colors.commandSurface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = if (emphasized) BorderStroke(1.dp, focusBoundary) else null,
        ) {
            Box(
                modifier = Modifier.padding(contentPadding),
                content = content,
            )
        }
    }
}
