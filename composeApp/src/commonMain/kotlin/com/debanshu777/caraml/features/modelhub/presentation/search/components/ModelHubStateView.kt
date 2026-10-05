package com.debanshu777.caraml.features.modelhub.presentation.search.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.core.theme.CaraMLTheme
import com.debanshu777.caraml.core.theme.ThemePreferences
import com.debanshu777.caraml.core.ui.components.BrandPal
import com.debanshu777.caraml.core.ui.components.BrandPalState
import com.debanshu777.caraml.core.ui.components.CaraMLPane
import com.debanshu777.caraml.core.ui.motion.LocalAuroraMotionPolicy

enum class ModelHubStateKind {
    Loading,
    Empty,
    Error,
}

@Composable
internal fun ModelHubLoadingMark(modifier: Modifier = Modifier) {
    if (LocalAuroraMotionPolicy.current.pulseEnabled) {
        CircularProgressIndicator(modifier = modifier)
    } else {
        Icon(Icons.Outlined.HourglassEmpty, contentDescription = null, modifier = modifier)
    }
}

@Composable
fun ModelHubStateView(
    kind: ModelHubStateKind,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    title: String? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    require((actionLabel == null) == (onAction == null)) {
        "actionLabel and onAction must either both be provided or both be null"
    }
    require((secondaryActionLabel == null) == (onSecondaryAction == null))
    if (kind == ModelHubStateKind.Loading) {
        Column(modifier.fillMaxWidth().testTag("model-results").semantics { stateDescription = kind.stateDescription }) {
            Text(message, style = AppTheme.typography.bodySmall, color = AppTheme.colors.onSurface)
            repeat(3) {
                Column(Modifier.fillMaxWidth().padding(vertical = AppTheme.spacing.spacing24), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    listOf(.62f to 20.dp, 1f to 11.dp, .4f to 11.dp).forEach { (width, height) ->
                        Box(Modifier.fillMaxWidth(width).height(height).clip(AppTheme.shapes.extraSmall)
                            .background(AppTheme.colors.onSurfaceVariant.copy(alpha = .14f)))
                    }
                }
                androidx.compose.material3.HorizontalDivider(color = AppTheme.colors.outlineVariant)
            }
        }
        return
    }
        Column(
            modifier = modifier
                .fillMaxWidth()
                .testTag("model-results")
                .semantics { stateDescription = kind.stateDescription }
                .padding(horizontal = AppTheme.spacing.spacing16, vertical = 35.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing8),
        ) {
            BrandPal(
                state = when (kind) {
                    ModelHubStateKind.Loading -> BrandPalState.Loading
                    ModelHubStateKind.Empty -> BrandPalState.Idle
                    ModelHubStateKind.Error -> BrandPalState.Error
                },
                modifier = Modifier.size(46.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = title ?: when (kind) {
                    ModelHubStateKind.Loading -> "Finding your next little brain"
                    ModelHubStateKind.Empty -> "Nothing here. Yet."
                    ModelHubStateKind.Error -> "The hub is taking a breather"
                },
                style = AppTheme.typography.stateTitle26,
                color = AppTheme.colors.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = message,
                style = AppTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = AppTheme.colors.onSurface,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing8), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing8)) {
              if (actionLabel != null && onAction != null) {
                FilledTonalButton(
                    onClick = onAction,
                    modifier = Modifier.heightIn(min = AppTheme.spacing.spacing48),
                    shape = AppTheme.shapes.small,
                    colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                        containerColor = AppTheme.colors.primary,
                        contentColor = AppTheme.colors.onPrimary,
                    ),
                ) {
                    Text(actionLabel)
                }
              }
              if (secondaryActionLabel != null && onSecondaryAction != null) {
                  ModelHubAction(secondaryActionLabel, onSecondaryAction)
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

@Preview(name = "Model hub loading", widthDp = 320)
@Composable
private fun ModelHubLoadingPreview() {
    CaraMLTheme(ThemePreferences()) {
        ModelHubStateView(ModelHubStateKind.Loading, "Loading models")
    }
}

@Preview(name = "Model hub search empty", widthDp = 320)
@Composable
private fun ModelHubEmptyPreview() {
    CaraMLTheme(ThemePreferences()) {
        ModelHubStateView(ModelHubStateKind.Empty, "No models match your search.", actionLabel = "Clear query", onAction = {})
    }
}

@Preview(name = "Model hub retry", widthDp = 320, fontScale = 1.5f)
@Composable
private fun ModelHubErrorPreview() {
    CaraMLTheme(ThemePreferences()) {
        ModelHubStateView(ModelHubStateKind.Error, "Couldn't reach the model hub. Your local models are still available.", actionLabel = "Retry", onAction = {})
    }
}
