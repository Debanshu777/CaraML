package com.debanshu777.caraml.features.modelhub.presentation.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import com.debanshu777.caraml.core.ui.motion.modelIdentityTransition
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.core.ui.icons.AppIcons
import com.debanshu777.caraml.features.modelhub.presentation.search.components.*

@Composable
internal fun ModelRow(
    state: ModelRowUiState?,
    onOpenModel: () -> Unit,
    modifier: Modifier = Modifier,
    onToggleSelect: () -> Unit = {},
    onLongPress: (() -> Unit)? = null,
    sharedTransitionKey: String? = null,
    onFixComponents: (() -> Unit)? = null,
    onRecommendationInfoClick: (() -> Unit)? = null,
) {
    state ?: return
    val role = if (state.selectionMode) Role.Checkbox else Role.Button
    ModelHubCard(
        title = state.title, task = state.task, emphasized = state.isSelected,
        titleModifier = Modifier.modelIdentityTransition(sharedTransitionKey),
        modifier = modifier.then(state.testTag?.let { Modifier.testTag(it) } ?: Modifier)
            .combinedClickable(role = role, onLongClick = onLongPress, onClick = {
                if (state.selectionMode) onToggleSelect() else if (state.canOpen) onOpenModel()
            }).semantics(mergeDescendants = true) {
                contentDescription = state.description
                if (state.selectionMode) selected = state.isSelected
            },
        leading = when {
            state.selectionMode -> ({ Icon(if (state.isSelected) AppIcons.Checkbox else AppIcons.CheckboxEmpty, null, tint = AppTheme.actionColor) })
            state.avatar != null -> ({ ModelAuthorAvatar(state.avatar.url, state.avatar.author, task = state.task,
                modifier = Modifier.modelIdentityTransition(sharedTransitionKey, avatar = true)) })
            else -> null
        },
        metrics = if (state.metrics.isEmpty()) null else ({ state.metrics.forEach { ModelMetric(it.icon, it.value, it.description) } }),
        status = if (state.statuses.isEmpty()) null else ({
            Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing4)) {
                state.statuses.forEach { status ->
                    val dark = AppTheme.colors.background.luminance() < .5f
                    val color = when (status.tone) {
                        ModelRowTone.Positive -> AppTheme.brandColors.positiveText(dark)
                        ModelRowTone.Caution -> AppTheme.brandColors.cautionText(dark)
                        ModelRowTone.Negative -> AppTheme.colors.error
                        ModelRowTone.Neutral -> AppTheme.colors.onSurfaceVariant
                    }
                    Box(if (state.recommendationAction && onRecommendationInfoClick != null) Modifier.heightIn(min = AppTheme.spacing.spacing48)
                        .clickable(role = Role.Button, onClick = onRecommendationInfoClick)
                        .semantics { contentDescription = "Open recommendation details" } else Modifier) {
                        ModelStatusIndicator(status.label, status.icon, color, Modifier.semantics {
                            contentDescription = status.description; stateDescription = status.description
                        })
                    }
                }
            }
        }),
        action = if (state.canRepair && onFixComponents != null) ({ ModelHubAction("Finish setup", onClick = onFixComponents) }) else null,
    )
}
