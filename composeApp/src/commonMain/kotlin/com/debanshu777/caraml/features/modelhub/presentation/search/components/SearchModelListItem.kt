package com.debanshu777.caraml.features.modelhub.presentation.search.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.debanshu777.caraml.core.recommendation.RecommendationCategory
import com.debanshu777.caraml.features.modelhub.domain.DescriptorState
import com.debanshu777.caraml.features.modelhub.domain.RecommendedModelUiState
import com.debanshu777.huggingfacemanager.model.SearchModelsResponse

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
            ModelRecommendationStatus(
                state = recommendationState?.descriptorState ?: DescriptorState.NEEDS_INFORMATION,
                recommendation = recommendationState?.personalizedResult,
                onInfoClick = onRecommendationInfoClick,
            )
        },
        onClick = onClick,
        modifier = modifier,
        highlighted = recommendationState?.personalizedResult?.category ==
            RecommendationCategory.RECOMMENDED,
        trailing = {
            recommendationState?.selectedVariantName?.let {
                Text("Selected variant: $it", style = MaterialTheme.typography.bodySmall)
            }
        },
    )
}
