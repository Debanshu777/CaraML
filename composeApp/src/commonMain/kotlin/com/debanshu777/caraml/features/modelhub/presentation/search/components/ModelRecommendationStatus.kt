package com.debanshu777.caraml.features.modelhub.presentation.search.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.core.rating.ui.recommendationCategoryLabel
import com.debanshu777.caraml.core.rating.ui.recommendationSemantics
import com.debanshu777.caraml.core.recommendation.PersonalizedRecommendation
import com.debanshu777.caraml.core.recommendation.RecommendationCategory
import com.debanshu777.caraml.core.ui.components.StatusMark
import com.debanshu777.caraml.features.modelhub.domain.DescriptorState
import com.debanshu777.caraml.core.recommendation.BrowseFitEstimate
import com.debanshu777.caraml.core.recommendation.BrowseResourceFit
import com.debanshu777.caraml.core.rating.ui.formatBytesHuman

internal data class BrowseVerdictPresentation(
    val label: String,
    val description: String,
    val icon: ImageVector,
)

internal fun browseVerdict(estimate: BrowseFitEstimate): BrowseVerdictPresentation = when (estimate.memoryFit) {
    BrowseResourceFit.LIKELY_FIT -> BrowseVerdictPresentation("Likely fits", "Estimated memory may fit", Icons.Outlined.CheckCircle)
    BrowseResourceFit.TIGHT_FIT -> BrowseVerdictPresentation("Tight fit", "Estimated memory fit is tight", Icons.Outlined.WarningAmber)
    BrowseResourceFit.TOO_LARGE -> BrowseVerdictPresentation("Too large", "Estimated memory exceeds current budget",  Icons.Outlined.Block)
    BrowseResourceFit.UNKNOWN -> BrowseVerdictPresentation("Resource fit unknown", "There is not enough current resource evidence", Icons.Outlined.Info)
}

@Composable
internal fun ModelRecommendationStatus(
    state: DescriptorState,
    recommendation: PersonalizedRecommendation?,
    onInfoClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    browseFit: BrowseFitEstimate? = null,
    compact: Boolean = false,
    plain: Boolean = false,
) {
    val presentation = recommendationStatusPresentation(state, recommendation)
    val largeText = LocalDensity.current.fontScale >= 1.5f
    val interactionModifier = if (onInfoClick != null) {
        Modifier
            .heightIn(min = AppTheme.spacing.spacing48)
            .clickable(role = Role.Button, onClick = onInfoClick)
            .semantics { contentDescription = "Open recommendation details" }
    } else {
        Modifier
    }
    Box(
        modifier = modifier.then(interactionModifier),
        contentAlignment = Alignment.Center,
    ) {
        val browse = browseFit?.let(::browseVerdict)
        val needsCautionColors = browseFit?.memoryFit == BrowseResourceFit.TIGHT_FIT
        if (plain) {
            Text(
                text = browse?.label ?: presentation.label,
                style = AppTheme.typography.labelSmall,
                color = when {
                    browseFit?.memoryFit == BrowseResourceFit.LIKELY_FIT ||
                        recommendation?.category in setOf(RecommendationCategory.RECOMMENDED, RecommendationCategory.USABLE) -> AppTheme.brandColors.positiveText(AppTheme.colors.background.luminance() < .5f)
                    browseFit?.memoryFit == BrowseResourceFit.TOO_LARGE ||
                        recommendation?.category in setOf(RecommendationCategory.NOT_SUITABLE, RecommendationCategory.INCOMPATIBLE) -> AppTheme.colors.onSurface
                    else -> AppTheme.brandColors.cautionText(AppTheme.colors.background.luminance() < .5f)
                },
                modifier = Modifier.semantics {
                    stateDescription = browse?.description ?: presentation.stateDescription
                    contentDescription = browse?.description ?: presentation.stateDescription
                },
            )
        } else {
        StatusMark(
            label = (browse?.label ?: presentation.label).let { label ->
                when {
                    !compact -> label
                    largeText -> when (label) {
                        "Needs information" -> "Info"
                        "Resource fit unknown" -> "Unknown"
                        "Too large" -> "Large"
                        "Tight fit" -> "Tight"
                        "Recommended" -> "Best"
                        "Select variant" -> "Select"
                        else -> label
                    }
                    else -> when (label) {
                        "Needs information" -> "Needs info"
                        "Resource fit unknown" -> "Unknown fit"
                        "Select variant" -> "Select"
                        "Recommended" -> "Best fit"
                        else -> label
                    }
                }
            },
            contentDescription = browse?.description ?: presentation.stateDescription,
            icon = browse?.icon ?: presentation.icon,
            containerColorOverride = if (needsCautionColors) AppTheme.colors.primaryContainer else null,
            contentColorOverride = if (needsCautionColors) AppTheme.colors.onPrimaryContainer else null,
        )
        }
    }
}

private data class RecommendationStatusPresentation(
    val label: String,
    val stateDescription: String,
    val icon: ImageVector,
)

private fun recommendationStatusPresentation(
    state: DescriptorState,
    recommendation: PersonalizedRecommendation?,
): RecommendationStatusPresentation {
    if (state == DescriptorState.ASSESSED && recommendation != null) {
        return RecommendationStatusPresentation(
            label = recommendationCategoryLabel(recommendation.category),
            stateDescription = recommendationSemantics(recommendation),
            icon = recommendation.category.statusIcon,
        )
    }
    return when (state) {
        DescriptorState.PENDING,
        DescriptorState.CHECKING,
        -> RecommendationStatusPresentation(
            label = "Checking",
            stateDescription = "Checking model compatibility.",
            icon = Icons.Outlined.HourglassEmpty,
        )

        DescriptorState.SELECT_VARIANT -> RecommendationStatusPresentation(
            label = "Select variant",
            stateDescription = "Select a model variant to assess compatibility.",
            icon = Icons.Outlined.Tune,
        )

        DescriptorState.NEEDS_INFORMATION,
        DescriptorState.ASSESSED,
        -> RecommendationStatusPresentation(
            label = "Needs information",
            stateDescription = "Needs information. Compatibility has not been determined.",
            icon = Icons.AutoMirrored.Outlined.HelpOutline,
        )
    }
}

private val RecommendationCategory.statusIcon: ImageVector
    get() = when (this) {
        RecommendationCategory.RECOMMENDED -> Icons.Outlined.AutoAwesome
        RecommendationCategory.USABLE -> Icons.Outlined.CheckCircle
        RecommendationCategory.RISKY -> Icons.Outlined.WarningAmber
        RecommendationCategory.NOT_SUITABLE,
        RecommendationCategory.INCOMPATIBLE,
        -> Icons.Outlined.Block

        RecommendationCategory.NEEDS_INFORMATION -> Icons.Outlined.Info
    }
