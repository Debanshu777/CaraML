package com.debanshu777.caraml.features.chat.presentation.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.prism
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
    onMenuClick: (() -> Unit)? = null,
    generationMode: GenerationMode? = null,
    onGenerationModeSelected: ((GenerationMode) -> Unit)? = null,
) {
    require((generationMode == null) == (onGenerationModeSelected == null))

    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
    ) {
        val useCompactLargeTextLayout =
            generationMode != null &&
            onGenerationModeSelected != null &&
            maxWidth < 600.dp &&
            LocalDensity.current.fontScale >= 1.5f

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onMenuClick != null) {
                IconButton(
                    onClick = onMenuClick,
                    modifier = Modifier
                        .size(48.dp)
                        .semantics { contentDescription = "Open navigation menu" },
                ) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = null,
                    )
                }
            }
            if (!useCompactLargeTextLayout) {
                QuietHeaderTitle(title)
            }
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

@Composable
private fun QuietHeaderTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.prism.screenTitle,
        color = MaterialTheme.colorScheme.onSurface,
    )
}
