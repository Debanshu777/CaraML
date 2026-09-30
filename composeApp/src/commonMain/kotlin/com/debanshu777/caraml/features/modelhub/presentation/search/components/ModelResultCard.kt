package com.debanshu777.caraml.features.modelhub.presentation.search.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
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
import com.debanshu777.caraml.core.theme.prismShapes
import com.debanshu777.caraml.features.modelhub.domain.DescriptorState
import com.debanshu777.caraml.core.recommendation.BrowseFitEstimate
import com.debanshu777.caraml.core.recommendation.BrowseResourceFit
import com.debanshu777.caraml.core.rating.ui.formatBytesHuman

@Composable
fun ModelResultCard(
    title: String,
    author: String?,
    metadata: String,
    status: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    taskTag: String? = null,
) {
    val identity = remember(title, author) { repositoryIdentity(title, author) }
    val trailingContent: (@Composable () -> Unit)? = trailing?.let { content ->
        { Row(content = content) }
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.prismShapes.pane,
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.76f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        TechnicalListRow(
            title = identity.title,
            eyebrow = identity.owner,
            metadata = metadata,
            contentDescription = "Open model $title",
            emphasized = highlighted,
            signalTone = if (highlighted) SignalTone.Accent else null,
            onClick = onClick,
            modifier = Modifier.testTag("model-row:$title"),
            titleStatus = status,
            eyebrowTrailing = taskTag?.let { label ->
                {
                    Surface(
                        shape = MaterialTheme.prismShapes.status,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ) {
                        Text(label, style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                }
            },
            trailing = trailingContent,
        )
    }
}

internal fun browseEstimateLabel(estimate: BrowseFitEstimate?): String? = estimate?.let {
    buildList {
        add("Compatibility unverified")
        it.downloadBytes?.let { bytes -> add("Download ${formatBytesHuman(bytes)}") }
        add(
            when (it.memoryFit) {
                BrowseResourceFit.LIKELY_FIT -> "Memory may fit"
                BrowseResourceFit.TIGHT_FIT -> "Memory may be tight"
                BrowseResourceFit.TOO_LARGE -> "Estimated memory exceeds current budget"
                BrowseResourceFit.UNKNOWN -> "Memory fit unknown"
            },
        )
        if (!it.resourceSnapshotFresh) add("resource snapshot stale")
    }.joinToString(" · ")
}

internal data class BrowseVerdictPresentation(
    val label: String,
    val description: String,
    val tone: SignalTone,
    val icon: ImageVector,
)

internal fun browseVerdict(estimate: BrowseFitEstimate): BrowseVerdictPresentation = when (estimate.memoryFit) {
    BrowseResourceFit.LIKELY_FIT -> BrowseVerdictPresentation("Likely fits", "Estimated memory may fit", SignalTone.Positive, Icons.Outlined.CheckCircle)
    BrowseResourceFit.TIGHT_FIT -> BrowseVerdictPresentation("Tight fit", "Estimated memory fit is tight", SignalTone.Warning, Icons.Outlined.WarningAmber)
    BrowseResourceFit.TOO_LARGE -> BrowseVerdictPresentation("Too large", "Estimated memory exceeds current budget", SignalTone.Error, Icons.Outlined.Block)
    BrowseResourceFit.UNKNOWN -> BrowseVerdictPresentation("Resource fit unknown", "There is not enough current resource evidence", SignalTone.Neutral, Icons.Outlined.Info)
}

@Composable
internal fun ModelRecommendationStatus(
    state: DescriptorState,
    recommendation: PersonalizedRecommendation?,
    onInfoClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    browseFit: BrowseFitEstimate? = null,
    compact: Boolean = false,
) {
    val presentation = recommendationStatusPresentation(state, recommendation)
    val largeText = LocalDensity.current.fontScale >= 1.5f
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
        val browse = browseFit?.let(::browseVerdict)
        val needsCautionColors = browseFit?.memoryFit == BrowseResourceFit.TIGHT_FIT
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
            tone = browse?.tone ?: presentation.tone,
            icon = browse?.icon ?: presentation.icon,
            containerColorOverride = if (needsCautionColors) MaterialTheme.colorScheme.primaryContainer else null,
            contentColorOverride = if (needsCautionColors) MaterialTheme.colorScheme.onPrimaryContainer else null,
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
