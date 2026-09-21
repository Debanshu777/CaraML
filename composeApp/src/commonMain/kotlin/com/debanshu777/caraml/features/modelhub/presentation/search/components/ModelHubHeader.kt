package com.debanshu777.caraml.features.modelhub.presentation.search.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.prism

@Composable
fun ModelHubHeader(
    title: String,
    summary: String? = null,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    require((actionLabel == null) == (onAction == null)) {
        "actionLabel and onAction must either both be provided or both be null"
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag("model-summary"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.prism.sectionTitle,
                color = MaterialTheme.colorScheme.onSurface,
            )
            summary?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.prism.denseMetadata,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (actionLabel != null && onAction != null) {
            TextButton(
                onClick = onAction,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(actionLabel)
            }
        }
    }
}
