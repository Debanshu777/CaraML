package com.debanshu777.caraml.features.modelhub.presentation.search.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.debanshu777.caraml.core.recommendation.RecommendationCategory
import com.debanshu777.caraml.core.theme.AppTechnicalLabel
import com.debanshu777.caraml.features.modelhub.domain.DescriptorState
import com.debanshu777.caraml.features.modelhub.domain.RecommendedModelUiState
import com.debanshu777.huggingfacemanager.model.ListModelsResponse

@Composable
fun ModelListItem(
    model: ListModelsResponse.Model?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    recommendationState: RecommendedModelUiState? = null,
    onRecommendationInfoClick: (() -> Unit)? = null,
) {
    if (model == null) return
    ModelResultCard(
        title = model.id ?: "Unknown",
        author = model.author?.let { "by $it" },
        metadata = buildString {
            model.pipelineTag?.let(::append)
            if (isNotEmpty()) append(" • ")
            append("${model.downloads ?: 0} downloads")
            append(" • ${model.likes ?: 0} likes")
            model.numParameters?.let { append(" • ${formatParams(it)} params") }
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
                Text("Selected variant: $it", style = AppTechnicalLabel)
            }
        },
    )
}

private fun formatParams(params: Long): String {
    return when {
        params >= 1_000_000_000 -> "${params / 1_000_000_000}B"
        params >= 1_000_000 -> "${params / 1_000_000}M"
        params >= 1_000 -> "${params / 1_000}K"
        else -> params.toString()
    }
}
