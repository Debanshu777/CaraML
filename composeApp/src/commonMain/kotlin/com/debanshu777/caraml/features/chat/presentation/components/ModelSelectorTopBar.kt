package com.debanshu777.caraml.features.chat.presentation.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.features.chat.domain.GenerationMode

@Preview
@Composable
private fun ModelSelectorTopBarPreview() {
    MaterialTheme {
        Surface {
            ModelSelectorTopBar(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun ModelSelectorTopBar(
    title: String = "Create",
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER")
    onMenuClick: () -> Unit = {},
    generationMode: GenerationMode? = null,
    onGenerationModeSelected: ((GenerationMode) -> Unit)? = null,
) {
    require((generationMode == null) == (onGenerationModeSelected == null))

    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
    ) {
        if (
            generationMode != null &&
            onGenerationModeSelected != null &&
            maxWidth < 600.dp &&
            LocalDensity.current.fontScale >= 1.5f
        ) {
            GenerationModeSwitcher(
                mode = generationMode,
                onModeSelected = onGenerationModeSelected,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .padding(vertical = 4.dp),
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                QuietHeaderTitle(title)
                if (generationMode != null && onGenerationModeSelected != null) {
                    GenerationModeSwitcher(
                        mode = generationMode,
                        onModeSelected = onGenerationModeSelected,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun QuietHeaderTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
}
