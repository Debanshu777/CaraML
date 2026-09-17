package com.debanshu777.caraml.features.settings.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.debanshu777.caraml.core.recommendation.OptimizationPriority
import com.debanshu777.caraml.core.recommendation.RecommendationProfile
import com.debanshu777.caraml.core.recommendation.RiskTolerance
import com.debanshu777.caraml.core.theme.LocalSpacing

private val riskChoices = listOf(
    RiskTolerance.CONSERVATIVE,
    RiskTolerance.BALANCED,
    RiskTolerance.EXPERIMENTAL,
)

private val priorityChoices = listOf(
    OptimizationPriority.SPEED_EFFICIENCY,
    OptimizationPriority.BALANCED,
    OptimizationPriority.QUALITY_CONTEXT,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecommendationProfileSection(
    profile: RecommendationProfile,
    onRiskToleranceChange: (RiskTolerance) -> Unit,
    onOptimizationPriorityChange: (OptimizationPriority) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    listStyle: Boolean = false,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "Selected risk: ${profile.riskTolerance.label()}. " +
                    "Selected priority: ${profile.optimizationPriority.label()}."
            },
        verticalArrangement = if (listStyle) {
            Arrangement.Top
        } else {
            Arrangement.spacedBy(spacing.m)
        },
    ) {
        if (!listStyle) {
            Text(
                text = "Recommendation profile",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Choose how cautiously CaraML rates device fit and what it optimizes for.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ChoiceGroup(
            title = "Risk tolerance",
            choices = riskChoices,
            selected = profile.riskTolerance,
            label = RiskTolerance::label,
            description = RiskTolerance::description,
            enabled = enabled,
            accessibilityPrefix = "Risk tolerance",
            onSelect = onRiskToleranceChange,
            listStyle = listStyle,
            dividerTag = "settings-divider-recommendation-risk",
        )

        ChoiceGroup(
            title = "Optimization priority",
            choices = priorityChoices,
            selected = profile.optimizationPriority,
            label = OptimizationPriority::label,
            description = OptimizationPriority::description,
            enabled = enabled,
            accessibilityPrefix = "Optimization priority",
            onSelect = onOptimizationPriorityChange,
            listStyle = listStyle,
            dividerTag = "settings-divider-recommendation-priority",
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceGroup(
    title: String,
    choices: List<T>,
    selected: T,
    label: (T) -> String,
    description: (T) -> String,
    enabled: Boolean,
    accessibilityPrefix: String,
    onSelect: (T) -> Unit,
    listStyle: Boolean,
    dividerTag: String,
) {
    val spacing = LocalSpacing.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = if (listStyle) {
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = spacing.l)
            } else {
                Modifier.fillMaxWidth()
            },
            verticalArrangement = Arrangement.spacedBy(spacing.s),
        ) {
            Text(text = title, style = MaterialTheme.typography.labelLarge)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.s),
                verticalArrangement = Arrangement.spacedBy(spacing.s),
            ) {
                choices.forEach { choice ->
                    val isSelected = choice == selected
                    SettingFilterChip(
                        selected = isSelected,
                        onClick = { onSelect(choice) },
                        label = label(choice),
                        selectedIndicatorContentDescription =
                            "Selected ${accessibilityPrefix.lowercase()} ${label(choice)}",
                        enabled = enabled,
                        modifier = Modifier
                            .semantics {
                                contentDescription = "$accessibilityPrefix ${label(choice)}, " +
                                    if (isSelected) "selected" else "not selected"
                                stateDescription = if (isSelected) "Selected" else "Not selected"
                            },
                    )
                }
            }
            Text(
                text = description(selected),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (listStyle) {
            SettingsRowDivider(tag = dividerTag)
        }
    }
}

fun RiskTolerance.label(): String = when (this) {
    RiskTolerance.CONSERVATIVE -> "Conservative"
    RiskTolerance.BALANCED -> "Balanced"
    RiskTolerance.EXPERIMENTAL -> "Experimental"
}

private fun RiskTolerance.description(): String = when (this) {
    RiskTolerance.CONSERVATIVE -> "Prefers comfortable memory headroom and proven configurations."
    RiskTolerance.BALANCED -> "Balances device headroom with access to capable models."
    RiskTolerance.EXPERIMENTAL -> "Allows tighter fits and clearly flags the added risk."
}

fun OptimizationPriority.label(): String = when (this) {
    OptimizationPriority.SPEED_EFFICIENCY -> "Speed & efficiency"
    OptimizationPriority.BALANCED -> "Balanced"
    OptimizationPriority.QUALITY_CONTEXT -> "Quality & context"
}

private fun OptimizationPriority.description(): String = when (this) {
    OptimizationPriority.SPEED_EFFICIENCY -> "Prioritizes faster responses and lower resource use."
    OptimizationPriority.BALANCED -> "Balances responsiveness, quality, and context capacity."
    OptimizationPriority.QUALITY_CONTEXT -> "Prioritizes output quality and longer context."
}
