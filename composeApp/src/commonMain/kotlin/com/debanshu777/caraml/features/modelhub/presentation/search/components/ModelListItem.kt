package com.debanshu777.caraml.features.modelhub.presentation.search.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.debanshu777.caraml.core.recommendation.RecommendationCategory
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
        metadata = listOfNotNull(
            model.numParameters?.let { "${formatParams(it)} parameters" },
            model.downloads?.let { "${formatCompactMetric(it.toLong())} downloads" },
        ).joinToString(" · "),
        taskTag = model.pipelineTag?.takeUnless { it.equals("text-generation", ignoreCase = true) }
            ?.let(::humanReadableTask),
        status = {
            ModelRecommendationStatus(
                state = recommendationState?.descriptorState ?: DescriptorState.NEEDS_INFORMATION,
                recommendation = recommendationState?.personalizedResult,
                onInfoClick = onRecommendationInfoClick,
                browseFit = recommendationState?.browseFit,
                compact = true,
            )
        },
        onClick = onClick,
        modifier = modifier,
        highlighted = recommendationState?.personalizedResult?.category ==
            RecommendationCategory.RECOMMENDED,
    )
}

private fun humanReadableTask(tag: String): String = when (tag.lowercase()) {
    "image-text-to-text" -> "Image + text"
    "text-to-image" -> "Text → image"
    "image-to-image" -> "Image → image"
    "text-to-video" -> "Text → video"
    "image-to-video" -> "Image → video"
    else -> tag.replace('-', ' ').replace('_', ' ').replaceFirstChar { it.uppercase() }
}

private fun formatParams(params: Long): String {
    return when {
        params >= 1_000_000_000 -> "${params / 1_000_000_000}B"
        params >= 1_000_000 -> "${params / 1_000_000}M"
        params >= 1_000 -> "${params / 1_000}K"
        else -> params.toString()
    }
}
