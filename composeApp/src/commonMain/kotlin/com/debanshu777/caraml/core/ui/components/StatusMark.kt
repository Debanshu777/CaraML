package com.debanshu777.caraml.core.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

@Composable
fun StatusMark(
    label: String,
    contentDescription: String,
    tone: SignalTone,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    val (containerColor, contentColor) = statusMarkColors(tone)
    Surface(
        modifier = modifier.semantics(mergeDescendants = true) {
            stateDescription = contentDescription
        },
        shape = MaterialTheme.shapes.extraSmall,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun statusMarkColors(tone: SignalTone): Pair<Color, Color> = MaterialTheme.colorScheme.run {
    when (tone) {
        SignalTone.Accent -> primaryContainer to onPrimaryContainer
        SignalTone.Positive -> tertiaryContainer to onTertiaryContainer
        SignalTone.Warning -> secondaryContainer to onSecondaryContainer
        SignalTone.Error -> errorContainer to onErrorContainer
        SignalTone.Neutral -> surfaceContainerHigh to onSurfaceVariant
    }
}
