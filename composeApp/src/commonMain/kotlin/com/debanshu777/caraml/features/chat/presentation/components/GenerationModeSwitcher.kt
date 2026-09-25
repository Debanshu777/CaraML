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
import com.debanshu777.caraml.core.theme.auroraColors
import com.debanshu777.caraml.core.theme.prismShapes
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
    val auroraColors = MaterialTheme.auroraColors
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .selectableGroup(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.prismShapes.command,
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            GenerationMode.entries.forEach { item ->
                val selected = item == mode
                val label = item.displayLabel()
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clip(MaterialTheme.prismShapes.control)
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
                        auroraColors.focusPrimary.copy(alpha = 1f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
                    shape = MaterialTheme.prismShapes.control,
                ) {
                    Text(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) {
                            auroraColors.onFocusPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
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
