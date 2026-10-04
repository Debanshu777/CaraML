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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.AppTheme

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
    val heading: @Composable (Modifier) -> Unit = { headingModifier ->
        Column(modifier = headingModifier) {
            Text(
                text = title,
                style = AppTheme.typography.heading16,
                color = AppTheme.colors.onSurface,
            )
            summary?.let {
                Text(
                    text = it,
                    style = AppTheme.typography.body14,
                    color = AppTheme.colors.onSurfaceVariant,
                )
            }
        }
    }
    val action: @Composable () -> Unit = {
        if (actionLabel != null && onAction != null) {
            TextButton(
                onClick = onAction,
                modifier = Modifier.heightIn(min = AppTheme.spacing.spacing48),
            ) {
                Text(actionLabel)
            }
        }
    }
    val containerModifier = modifier.fillMaxWidth().testTag("model-summary")
    if (LocalDensity.current.fontScale >= 1.4f) {
        Column(modifier = containerModifier) {
            heading(Modifier.fillMaxWidth())
            action()
        }
    } else {
        Row(
            modifier = containerModifier,
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            heading(Modifier.weight(1f))
            action()
        }
    }
}
