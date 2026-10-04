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
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.AppTheme

@Composable
fun GenericListItem(
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    metadata: String? = null,
    contentDescription: String? = null,
    selected: Boolean = false,
    emphasized: Boolean = selected,
    selectionEnabled: Boolean = selected,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    status: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    titleStatus: (@Composable () -> Unit)? = null,
    eyebrowTrailing: (@Composable () -> Unit)? = null,
) {
    val colors = AppTheme.auroraColors
    val spacing = AppTheme.spacing
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
        val stackAccessories = maxWidth < AppTheme.dimensions.size480 ||
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
                        .padding(vertical = spacing.spacing12),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    leading?.let {
                        Box(
                            modifier = Modifier.size(AppTheme.spacing.spacing24),
                            contentAlignment = Alignment.Center,
                        ) {
                            it()
                        }
                        Spacer(modifier = Modifier.width(spacing.spacing12))
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(spacing.spacing4),
                    ) {
                    if (eyebrow != null || eyebrowTrailing != null) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            eyebrow?.let {
                                Text(
                                    text = it,
                                    style = AppTheme.typography.technical12,
                                    color = AppTheme.colors.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            eyebrowTrailing?.invoke()
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = title,
                            style = AppTheme.typography.body17,
                            color = AppTheme.colors.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        titleStatus?.let {
                            Spacer(modifier = Modifier.width(spacing.spacing8))
                            it()
                        }
                    }
                    metadata?.let {
                        Text(
                            text = it,
                            style = AppTheme.typography.body14,
                            color = AppTheme.colors.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = spacing.spacing4),
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
                                    Spacer(modifier = Modifier.width(spacing.spacing8))
                                    it()
                                }
                            }
                        }
                    }
                    if (!stackAccessories) {
                        status?.let {
                            Spacer(modifier = Modifier.width(spacing.spacing8))
                            it()
                        }
                        trailing?.let {
                            Spacer(modifier = Modifier.width(spacing.spacing8))
                            it()
                        }
                    }
                }
                HorizontalDivider(
                    modifier = Modifier.fillMaxWidth(),
                    thickness = AppTheme.dimensions.size1,
                    color = colors.divider,
                )
            }
        }
    }
}

@Preview(name = "Compact variants", widthDp = 360)
@Composable
private fun GenericListItemCompactPreview() {
    MaterialTheme {
        Column {
            GenericListItem(
                title = "Qwen 2.5 7B",
                eyebrow = "QWEN",
                metadata = "Q4_K_M · 4.7 GB",
                eyebrowTrailing = { PreviewTaskTag("Image to text") },
                trailing = { Button(onClick = {}) { Text("Download") } },
            )
            GenericListItem(
                title = "Recommended artifact",
                eyebrow = "qwen2.5-7b-instruct-q4_k_m.gguf",
                emphasized = true,
                selectionEnabled = true,
                onClick = {},
                status = { Text("Recommended") },
                trailing = { Text("4.7 GB") },
                eyebrowTrailing = { PreviewTaskTag("Text generation") },
            )
            GenericListItem(
                title = "Selected artifact",
                eyebrow = "qwen2.5-7b-instruct-q5_k_m.gguf",
                metadata = "Q5_K_M · 5.1 GB",
                selected = true,
                onClick = {},
                leading = { Text("✓") },
                status = { Text("Installed") },
                trailing = { Text("5.1 GB") },
            )
        }
    }
}

@Preview(name = "Wide accessories", widthDp = 600)
@Composable
private fun GenericListItemWidePreview() {
    MaterialTheme {
        Column {
            GenericListItem(
                title = "Qwen 2.5 7B",
                eyebrow = "QWEN",
                metadata = "Q4_K_M · 4.7 GB",
                onClick = {},
                leading = { Text("AI") },
                titleStatus = { Text("Ready") },
                eyebrowTrailing = { PreviewTaskTag("Image to text") },
                trailing = { Button(onClick = {}) { Text("Download") } },
            )
            GenericListItem(
                title = "Downloading artifact",
                eyebrow = "qwen2.5-7b-instruct-q5_k_m.gguf",
                metadata = "Q5_K_M · 5.1 GB",
                status = { Text("Downloading") },
                trailing = { Text("42%") },
            )
        }
    }
}

@Composable
private fun PreviewTaskTag(label: String) {
    Surface(
        shape = AppTheme.shapes.extraSmall,
        color = AppTheme.colors.secondaryContainer,
        contentColor = AppTheme.colors.onSecondaryContainer,
    ) {
        Text(
            text = label,
            style = AppTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = AppTheme.spacing.spacing8, vertical = AppTheme.dimensions.size3),
        )
    }
}
