package com.debanshu777.caraml.features.modelhub.presentation.search.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.AppTheme

enum class ModelHubStateKind {
    Loading,
    Empty,
    Error,
}

@Composable
fun ModelHubStateView(
    kind: ModelHubStateKind,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    require((actionLabel == null) == (onAction == null)) {
        "actionLabel and onAction must either both be provided or both be null"
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("model-results")
            .semantics { stateDescription = kind.stateDescription }
            .padding(vertical = AppTheme.spacing.spacing24),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing8),
    ) {
        when (kind) {
            ModelHubStateKind.Loading -> CircularProgressIndicator()
            ModelHubStateKind.Empty -> Icon(
                imageVector = Icons.Outlined.Inventory2,
                contentDescription = null,
                tint = AppTheme.colors.onSurfaceVariant,
            )
            ModelHubStateKind.Error -> Icon(
                imageVector = Icons.Outlined.CloudOff,
                contentDescription = null,
                tint = AppTheme.colors.error,
            )
        }
        Text(
            text = message,
            style = AppTheme.typography.bodyBase,
            color = if (kind == ModelHubStateKind.Error) {
                AppTheme.colors.error
            } else {
                AppTheme.colors.onSurfaceVariant
            },
        )
        if (actionLabel != null && onAction != null) {
            TextButton(
                onClick = onAction,
                modifier = Modifier.heightIn(min = AppTheme.spacing.spacing48),
            ) {
                Text(actionLabel)
            }
        }
    }
}

private val ModelHubStateKind.stateDescription: String
    get() = when (this) {
        ModelHubStateKind.Loading -> "Loading model results"
        ModelHubStateKind.Empty -> "No model results"
        ModelHubStateKind.Error -> "Model results failed"
    }
