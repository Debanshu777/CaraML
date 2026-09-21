package com.debanshu777.caraml.core.drawer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.auroraColors
import com.debanshu777.caraml.core.theme.prismShapes
import com.debanshu777.caraml.core.ui.components.SignalRail
import com.debanshu777.caraml.core.ui.components.SignalTone

@Composable
fun DrawerItemView(
    item: DrawerItem,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
) {
    val colors = MaterialTheme.auroraColors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.prismShapes.control)
            .background(if (selected) colors.selectedSurface else androidx.compose.ui.graphics.Color.Transparent)
            .selectable(
                selected = selected,
                role = Role.Tab,
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) {
                contentDescription = if (selected) "${item.title}, selected" else item.title
                this.selected = selected
            },
    ) {
        if (selected) {
            SignalRail(tone = SignalTone.Accent)
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = if (showLabel) 16.dp else 12.dp, vertical = 12.dp),
            horizontalArrangement = if (showLabel) Arrangement.spacedBy(16.dp) else Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (showLabel) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}
