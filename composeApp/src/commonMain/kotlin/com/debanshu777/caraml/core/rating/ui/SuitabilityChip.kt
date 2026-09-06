package com.debanshu777.caraml.core.rating.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.debanshu777.caraml.core.recommendation.PersonalizedRecommendation
import com.debanshu777.caraml.features.modelhub.domain.DescriptorState
import com.debanshu777.caraml.core.rating.SuitabilityRating

@Composable
fun RecommendationStatusChip(
    state: DescriptorState,
    recommendation: PersonalizedRecommendation?,
    modifier: Modifier = Modifier,
    onInfoClick: (() -> Unit)? = null,
) {
    if (state == DescriptorState.ASSESSED && recommendation != null) {
        SuitabilityChip(recommendation, modifier, onInfoClick)
    } else {
        Surface(
            modifier = modifier.heightIn(min = 48.dp),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Text(
                text = when (state) {
                    DescriptorState.PENDING, DescriptorState.CHECKING -> "Checking"
                    DescriptorState.SELECT_VARIANT -> "Select variant"
                    DescriptorState.NEEDS_INFORMATION -> "Needs information"
                    DescriptorState.ASSESSED -> "Needs information"
                },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/**
 * Compact chip showing a model's [SuitabilityRating]. Optional info button
 * opens the bottom-sheet explainer.
 *
 * Layout: [colored dot] [label] [optional info icon]
 *
 * Pass null `onInfoClick` for a non-interactive chip (e.g. inside the variant
 * picker where a single shared sheet is opened elsewhere).
 */
@Composable
fun SuitabilityChip(
    recommendation: PersonalizedRecommendation,
    modifier: Modifier = Modifier,
    onInfoClick: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.heightIn(min = 48.dp).semantics { contentDescription = recommendationSemantics(recommendation) },
        shape = MaterialTheme.shapes.small,
        color = recommendation.category.containerColor(),
        contentColor = recommendation.category.onContainerColor(),
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .let { if (onInfoClick != null) it.clickable { onInfoClick() } else it }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                recommendationCategoryLabel(recommendation.category),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
            )
            if (onInfoClick != null) {
                Icon(Icons.Outlined.Info, contentDescription = "Recommendation details", modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** Temporary adapter for call sites that still expose the legacy rating. */
@Composable
fun SuitabilityChip(
    rating: SuitabilityRating,
    modifier: Modifier = Modifier,
    onInfoClick: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = rating.containerColor(),
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .let { if (onInfoClick != null) it.clickable { onInfoClick() } else it }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(rating.foregroundColor()),
            )
            Text(
                text = rating.shortLabel(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = rating.onContainerColor(),
            )
            if (onInfoClick != null) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = "About this rating",
                    tint = rating.onContainerColor(),
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}
