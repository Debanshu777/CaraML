package com.debanshu777.caraml.features.modelhub.presentation.search.components

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import com.debanshu777.caraml.core.ui.components.BrandIconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.debanshu777.caraml.core.theme.AppTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelSearchAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = 22.dp,
    badge: Int? = null,
    compactBadge: Boolean = false,
    badgeDescription: String = "",
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            PlainTooltip {
                Text(if (badge != null) "$label · $badgeDescription" else label)
            }
        },
        state = rememberTooltipState(),
    ) {
        Box(Modifier.size(AppTheme.spacing.spacing48)) {
            BrandIconButton(
                onClick = onClick,
                modifier = modifier.matchParentSize().semantics {
                    if (badge != null) stateDescription = badgeDescription
                },
            ) {
                Icon(icon, contentDescription = label, modifier = Modifier.size(iconSize), tint = AppTheme.colors.onSurface)
            }
            if (badge != null && compactBadge) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 7.dp)
                        .size(6.dp).background(AppTheme.colors.primary, CircleShape)
                        .clearAndSetSemantics {},
                )
            } else if (badge != null) {
                Badge(
                    containerColor = AppTheme.colors.primary,
                    contentColor = AppTheme.colors.onPrimary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 3.dp, end = 2.dp)
                        .clearAndSetSemantics {},
                ) {
                    Text(if (badge > 99) "99+" else badge.toString())
                }
            }
        }
    }
}
