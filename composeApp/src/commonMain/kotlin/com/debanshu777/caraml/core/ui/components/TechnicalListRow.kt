package com.debanshu777.caraml.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.AppTechnicalLabel
import com.debanshu777.caraml.core.theme.auroraColors

@Composable
fun TechnicalListRow(
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    metadata: String? = null,
    contentDescription: String? = null,
    selected: Boolean = false,
    emphasized: Boolean = selected,
    signalTone: SignalTone? = null,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    status: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.auroraColors
    val showSignal = emphasized || signalTone != null
    val stackAccessories = LocalDensity.current.fontScale >= 1.5f ||
        (status != null && trailing != null)
    val interactionModifier = if (onClick != null) {
        Modifier.selectable(
            selected = selected,
            role = Role.Button,
            onClick = onClick,
        )
    } else {
        Modifier
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(if (emphasized) colors.selectedSurface else Color.Transparent)
            .then(interactionModifier)
            .semantics(mergeDescendants = true) {
                this.selected = selected
                contentDescription?.let { this.contentDescription = it }
            },
    ) {
        if (showSignal) {
            SignalRail(tone = signalTone ?: SignalTone.Accent)
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                leading?.let {
                    Box(
                        modifier = Modifier.size(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        it()
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    eyebrow?.let {
                        Text(
                            text = it,
                            style = AppTechnicalLabel,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    metadata?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (stackAccessories && (status != null || trailing != null)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            status?.invoke()
                            trailing?.let {
                                Spacer(modifier = Modifier.width(8.dp))
                                it()
                            }
                        }
                    }
                }
                if (!stackAccessories) {
                    status?.let {
                        Spacer(modifier = Modifier.width(8.dp))
                        it()
                    }
                    trailing?.let {
                        Spacer(modifier = Modifier.width(8.dp))
                        it()
                    }
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(start = if (leading == null) 16.dp else 52.dp),
                thickness = 1.dp,
                color = colors.divider,
            )
        }
    }
}
