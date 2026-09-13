package com.debanshu777.caraml.features.modelhub.presentation.search.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.debanshu777.huggingfacemanager.model.SearchModelsResponse
import com.debanshu777.caraml.core.recommendation.RecommendationCategory
import com.debanshu777.caraml.features.modelhub.domain.RecommendedModelUiState
import com.debanshu777.caraml.features.modelhub.domain.DescriptorState
import com.debanshu777.caraml.core.rating.ui.RecommendationStatusChip

@Composable
fun SearchModelListItem(
    model: SearchModelsResponse.Model?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    recommendationState: RecommendedModelUiState? = null,
    onRecommendationInfoClick: (() -> Unit)? = null,
) {
    if (model == null) return
    ModelResultCard(
        title = model.id ?: "Unknown",
        author = null,
        metadata = buildString {
            append("Trending weight: ${model.trendingWeight ?: 0}")
            if (model.`private` == true) {
                append(" • Private")
            }
        },
        status = {
            RecommendationStatusChip(
                recommendationState?.descriptorState ?: DescriptorState.NEEDS_INFORMATION,
                recommendationState?.personalizedResult,
                onInfoClick = onRecommendationInfoClick,
            )
        },
        onClick = onClick,
        modifier = modifier.padding(vertical = 5.dp),
        highlighted = recommendationState?.personalizedResult?.category ==
            RecommendationCategory.RECOMMENDED,
        trailing = {
            recommendationState?.selectedVariantName?.let {
                Text("Selected variant: $it", style = MaterialTheme.typography.labelSmall)
            }
        },
    )
}
