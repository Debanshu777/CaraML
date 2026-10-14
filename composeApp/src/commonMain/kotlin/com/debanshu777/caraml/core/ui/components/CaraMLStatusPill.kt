package com.debanshu777.caraml.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.AppTheme

enum class StatusTone {
    Neutral,
    Accent,
    Success,
    Warning,
    Error,
}

@Composable
fun CaraMLStatusPill(
    label: String,
    contentDescription: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val (containerColor, contentColor) = statusColors(tone)
    Surface(
        modifier = modifier.semantics(mergeDescendants = true) {
            this.contentDescription = contentDescription
        },
        shape = AppTheme.shapes.extraSmall,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = AppTheme.spacing.spacing8, vertical = AppTheme.spacing.spacing4),
            horizontalArrangement = Arrangement.Center,
        ) {
            icon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    modifier = Modifier.size(AppTheme.spacing.spacing16),
                )
                Spacer(modifier = Modifier.size(AppTheme.spacing.spacing4))
            }
            Text(text = label, style = AppTheme.typography.labelBase)
        }
    }
}

@Composable
private fun statusColors(tone: StatusTone): Pair<Color, Color> = AppTheme.colors.run {
    when (tone) {
        StatusTone.Neutral -> surfaceContainerHigh to onSurfaceVariant
        StatusTone.Accent -> primaryContainer to onPrimaryContainer
        StatusTone.Success -> tertiaryContainer to onTertiaryContainer
        StatusTone.Warning -> secondaryContainer to onSecondaryContainer
        StatusTone.Error -> errorContainer to onErrorContainer
    }
}
