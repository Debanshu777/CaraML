package com.debanshu777.caraml.features.modelhub.presentation.search.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.rating.ui.recommendationCategoryLabel
import com.debanshu777.caraml.core.rating.ui.recommendationSemantics
import com.debanshu777.caraml.core.recommendation.PersonalizedRecommendation
import com.debanshu777.caraml.core.recommendation.RecommendationCategory
import com.debanshu777.caraml.core.ui.components.SignalTone
import com.debanshu777.caraml.core.ui.components.StatusMark
import com.debanshu777.caraml.core.ui.components.TechnicalListRow
import com.debanshu777.caraml.features.modelhub.domain.DescriptorState

@Composable
fun ModelResultCard(
    title: String,
    author: String?,
    metadata: String,
    status: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val identity = remember(title, author) { repositoryIdentity(title, author) }
    TechnicalListRow(
        title = identity.title,
        eyebrow = identity.owner,
        metadata = metadata,
        contentDescription = "Open model $title",
        emphasized = highlighted,
        signalTone = if (highlighted) SignalTone.Accent else null,
        onClick = onClick,
        modifier = modifier.testTag("model-row:$title"),
        status = status,
        trailing = {
            Row(content = trailing)
        },
    )
}

@Composable
internal fun ModelRecommendationStatus(
    state: DescriptorState,
    recommendation: PersonalizedRecommendation?,
    onInfoClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val presentation = recommendationStatusPresentation(state, recommendation)
    val interactionModifier = if (onInfoClick != null) {
        Modifier
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onInfoClick)
            .semantics { contentDescription = "Open recommendation details" }
    } else {
        Modifier
    }
    Box(
        modifier = modifier.then(interactionModifier),
        contentAlignment = Alignment.Center,
    ) {
        StatusMark(
            label = presentation.label,
            contentDescription = presentation.stateDescription,
            tone = presentation.tone,
            icon = presentation.icon,
        )
    }
}

private data class RepositoryIdentity(
    val owner: String?,
    val title: String,
)

private fun repositoryIdentity(repositoryId: String, author: String?): RepositoryIdentity {
    val inferredOwner = repositoryId.substringBefore('/', missingDelimiterValue = "")
        .takeIf(String::isNotBlank)
    val owner = author?.removePrefix("by ")?.takeIf(String::isNotBlank) ?: inferredOwner
    return RepositoryIdentity(
        owner = owner,
        title = repositoryId.substringAfter('/', missingDelimiterValue = repositoryId),
    )
}

private data class RecommendationStatusPresentation(
    val label: String,
    val stateDescription: String,
    val tone: SignalTone,
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
            tone = recommendation.category.signalTone,
            icon = recommendation.category.statusIcon,
        )
    }
    return when (state) {
        DescriptorState.PENDING,
        DescriptorState.CHECKING,
        -> RecommendationStatusPresentation(
            label = "Checking",
            stateDescription = "Checking model compatibility.",
            tone = SignalTone.Neutral,
            icon = Icons.Outlined.HourglassEmpty,
        )

        DescriptorState.SELECT_VARIANT -> RecommendationStatusPresentation(
            label = "Select variant",
            stateDescription = "Select a model variant to assess compatibility.",
            tone = SignalTone.Warning,
            icon = Icons.Outlined.Tune,
        )

        DescriptorState.NEEDS_INFORMATION,
        DescriptorState.ASSESSED,
        -> RecommendationStatusPresentation(
            label = "Needs information",
            stateDescription = "Needs information. Compatibility has not been determined.",
            tone = SignalTone.Neutral,
            icon = Icons.AutoMirrored.Outlined.HelpOutline,
        )
    }
}

private val RecommendationCategory.signalTone: SignalTone
    get() = when (this) {
        RecommendationCategory.RECOMMENDED -> SignalTone.Accent
        RecommendationCategory.USABLE -> SignalTone.Positive
        RecommendationCategory.RISKY -> SignalTone.Warning
        RecommendationCategory.NOT_SUITABLE,
        RecommendationCategory.INCOMPATIBLE,
        -> SignalTone.Error

        RecommendationCategory.NEEDS_INFORMATION -> SignalTone.Neutral
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
