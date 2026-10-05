package com.debanshu777.caraml.core.drawer

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
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
import com.debanshu777.caraml.core.theme.AppTheme

@Composable
fun DrawerItemView(
    item: DrawerItem,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .heightIn(min = 58.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) AppTheme.colors.primary else androidx.compose.ui.graphics.Color.Transparent)
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
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = if (showLabel) AppTheme.spacing.spacing16 else AppTheme.spacing.spacing12, vertical = AppTheme.spacing.spacing12),
            horizontalArrangement = if (showLabel) Arrangement.spacedBy(AppTheme.spacing.spacing16) else Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                modifier = Modifier.size(21.dp),
                tint = if (selected) {
                    AppTheme.colors.onPrimary
                } else {
                    AppTheme.colors.onSurfaceVariant
                },
            )
            if (showLabel) {
                Text(
                    text = item.title,
                    style = AppTheme.typography.bodyLarge,
                    color = if (selected) {
                        AppTheme.colors.onPrimary
                    } else {
                        AppTheme.colors.onSurfaceVariant
                    },
                )
            }
        }
    }
}
