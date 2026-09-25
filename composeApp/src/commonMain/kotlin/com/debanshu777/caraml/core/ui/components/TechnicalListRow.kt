package com.debanshu777.caraml.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.auroraColors
import com.debanshu777.caraml.core.theme.LocalSpacing
import com.debanshu777.caraml.core.theme.prism

@Composable
fun TechnicalListRow(
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    metadata: String? = null,
    contentDescription: String? = null,
    selected: Boolean = false,
    signalTone: SignalTone? = null,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    status: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    TechnicalListRowImpl(
        title = title,
        modifier = modifier,
        eyebrow = eyebrow,
        metadata = metadata,
        contentDescription = contentDescription,
        selected = selected,
        emphasized = selected,
        selectionEnabled = selected,
        signalTone = signalTone,
        onClick = onClick,
        leading = leading,
        status = status,
        trailing = trailing,
    )
}

/**
 * Source-compatible emphasized-row overload. [emphasized] is intentionally required so calls
 * using the original positional parameter order continue to resolve to the overload above.
 */
@Composable
fun TechnicalListRow(
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    metadata: String? = null,
    contentDescription: String? = null,
    selected: Boolean = false,
    emphasized: Boolean,
    signalTone: SignalTone? = null,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    status: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    TechnicalListRowImpl(
        title = title,
        modifier = modifier,
        eyebrow = eyebrow,
        metadata = metadata,
        contentDescription = contentDescription,
        selected = selected,
        emphasized = emphasized,
        selectionEnabled = selected,
        signalTone = signalTone,
        onClick = onClick,
        leading = leading,
        status = status,
        trailing = trailing,
    )
}

/** A row participating in a meaningful single-selection group. */
@Composable
fun TechnicalListRow(
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    metadata: String? = null,
    contentDescription: String? = null,
    selected: Boolean = false,
    emphasized: Boolean,
    selectionEnabled: Boolean,
    signalTone: SignalTone? = null,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    status: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    TechnicalListRowImpl(
        title = title,
        modifier = modifier,
        eyebrow = eyebrow,
        metadata = metadata,
        contentDescription = contentDescription,
        selected = selected,
        emphasized = emphasized,
        selectionEnabled = selectionEnabled,
        signalTone = signalTone,
        onClick = onClick,
        leading = leading,
        status = status,
        trailing = trailing,
    )
}

@Composable
private fun TechnicalListRowImpl(
    title: String,
    modifier: Modifier,
    eyebrow: String?,
    metadata: String?,
    contentDescription: String?,
    selected: Boolean,
    emphasized: Boolean,
    selectionEnabled: Boolean,
    signalTone: SignalTone?,
    onClick: (() -> Unit)?,
    leading: (@Composable () -> Unit)?,
    status: (@Composable () -> Unit)?,
    trailing: (@Composable () -> Unit)?,
) {
    val colors = MaterialTheme.auroraColors
    val spacing = LocalSpacing.current
    val showSignal = emphasized || signalTone != null
    val interactionModifier = when {
        selectionEnabled -> Modifier.selectable(
            selected = selected,
            enabled = onClick != null,
            role = Role.RadioButton,
            onClick = onClick ?: {},
        )
        onClick != null -> Modifier.clickable(
            role = Role.Button,
            onClick = onClick,
        )
        else -> Modifier
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val stackAccessories = maxWidth < 480.dp ||
            LocalDensity.current.fontScale >= 1.5f ||
            (status != null && trailing != null)
        Box(
            modifier = modifier
                .fillMaxWidth()
                .background(if (emphasized) colors.selectedSurface else Color.Transparent)
                .then(interactionModifier)
                .semantics(mergeDescendants = true) {
                    contentDescription?.let { this.contentDescription = it }
                },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.l, vertical = spacing.m),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    leading?.let {
                        Box(
                            modifier = Modifier.size(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            it()
                        }
                        Spacer(modifier = Modifier.width(spacing.m))
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(spacing.xs),
                    ) {
                    eyebrow?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.prism.technicalLabel,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.prism.modelTitle,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    metadata?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.prism.denseMetadata,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = spacing.xs),
                            softWrap = true,
                        )
                    }
                        if (stackAccessories && (status != null || trailing != null)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Start,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                status?.invoke()
                                trailing?.let {
                                    Spacer(modifier = Modifier.width(spacing.s))
                                    it()
                                }
                            }
                        }
                    }
                    if (!stackAccessories) {
                        status?.let {
                            Spacer(modifier = Modifier.width(spacing.s))
                            it()
                        }
                        trailing?.let {
                            Spacer(modifier = Modifier.width(spacing.s))
                            it()
                        }
                    }
                }
                HorizontalDivider(
                    modifier = Modifier.padding(
                        start = if (leading == null) spacing.l else spacing.l + 36.dp,
                    ),
                    thickness = 1.dp,
                    color = colors.divider,
                )
            }
            if (showSignal) {
                Box(Modifier.matchParentSize()) {
                    SignalRail(tone = signalTone ?: SignalTone.Accent)
                }
            }
        }
    }
}
