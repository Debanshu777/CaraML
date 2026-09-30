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
import com.debanshu777.caraml.core.theme.auroraColors
import com.debanshu777.caraml.core.theme.prismShapes

@Composable
fun CommandSurface(
    focused: Boolean,
    active: Boolean,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    idleContainerColor: Color? = null,
    idleBorderColor: Color? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = MaterialTheme.auroraColors
    val emphasized = focused || active
    val shape = MaterialTheme.prismShapes.command
    val outerTreatment = if (emphasized) {
        Modifier.background(color = colors.focusPrimary.copy(alpha = 0.20f), shape = shape)
    } else {
        Modifier.background(color = idleContainerColor ?: colors.commandSurface, shape = shape)
    }

    Box(
        modifier = modifier
            .then(outerTreatment)
            .padding(2.dp),
        propagateMinConstraints = true,
    ) {
        Surface(
            shape = shape,
            color = idleContainerColor ?: colors.commandSurface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = when {
                emphasized -> BorderStroke(1.dp, colors.focusPrimary)
                idleBorderColor != null -> BorderStroke(1.dp, idleBorderColor)
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
