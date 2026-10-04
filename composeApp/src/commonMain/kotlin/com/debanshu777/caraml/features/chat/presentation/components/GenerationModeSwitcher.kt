package com.debanshu777.caraml.features.chat.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.features.chat.domain.GenerationMode

/**
 * Local Create modes. These controls deliberately update generation state only; they are not
 * application destinations and therefore never own navigation callbacks.
 */
@Composable
fun GenerationModeSwitcher(
    mode: GenerationMode,
    onModeSelected: (GenerationMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val auroraColors = AppTheme.auroraColors
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .selectableGroup(),
        color = AppTheme.colors.surfaceContainerLow,
        shape = AppTheme.shapes.medium,
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            GenerationMode.entries.forEach { item ->
                val selected = item == mode
                val label = item.displayLabel()
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = AppTheme.spacing.spacing48)
                        .clip(AppTheme.shapes.small)
                        .selectable(
                            selected = selected,
                            onClick = { onModeSelected(item) },
                            role = Role.Tab,
                        )
                        .semantics(mergeDescendants = true) {
                            contentDescription = if (selected) {
                                "$label mode, selected"
                            } else {
                                "$label mode"
                            }
                        },
                    color = if (selected) {
                        auroraColors.focusPrimary.copy(alpha = AppTheme.effects.opaque)
                    } else {
                        AppTheme.colors.surfaceContainerLow
                    },
                    shape = AppTheme.shapes.small,
                ) {
                    Text(
                        modifier = Modifier.padding(horizontal = AppTheme.spacing.spacing8, vertical = AppTheme.spacing.spacing12),
                        text = label,
                        style = AppTheme.typography.labelLarge,
                        color = if (selected) {
                            auroraColors.onFocusPrimary
                        } else {
                            AppTheme.colors.onSurfaceVariant
                        },
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

private fun GenerationMode.displayLabel(): String = when (this) {
    GenerationMode.Text -> "Text"
    GenerationMode.Image -> "Image"
    GenerationMode.Video -> "Video"
}
