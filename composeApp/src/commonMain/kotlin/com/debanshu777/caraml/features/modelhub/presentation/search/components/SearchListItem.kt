package com.debanshu777.caraml.features.modelhub.presentation.search.components

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.core.recommendation.RecommendationCategory
import com.debanshu777.caraml.core.theme.CaraMLTheme
import com.debanshu777.caraml.core.theme.ThemePreferences
import com.debanshu777.caraml.core.ui.components.GenericListItem
import com.debanshu777.caraml.features.modelhub.domain.DescriptorState
import com.debanshu777.caraml.features.modelhub.domain.RecommendedModelUiState
import com.debanshu777.huggingfacemanager.model.ListModelsResponse

@Composable
fun SearchListItem(
    model: ListModelsResponse.Model?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    recommendationState: RecommendedModelUiState? = null,
    onRecommendationInfoClick: (() -> Unit)? = null,
) {
    if (model == null) return
    val repositoryId = model.id ?: "Unknown"
    val taskTag = model.pipelineTag?.takeUnless { it.equals("text-generation", ignoreCase = true) }
        ?.let(::humanReadableTask)
    GenericListItem(
        title = repositoryId.substringAfter('/', missingDelimiterValue = repositoryId),
        eyebrow = model.author?.takeIf(String::isNotBlank)
            ?: repositoryId.substringBefore('/', missingDelimiterValue = "").takeIf(String::isNotBlank),
        metadata = listOfNotNull(
            model.numParameters?.let { "${formatParams(it)} parameters" },
            model.downloads?.let { "${formatCompactMetric(it.toLong())} downloads" },
        ).joinToString(" · "),
        contentDescription = "Open model $repositoryId",
        titleStatus = {
            ModelRecommendationStatus(
                state = recommendationState?.descriptorState ?: DescriptorState.NEEDS_INFORMATION,
                recommendation = recommendationState?.personalizedResult,
                onInfoClick = onRecommendationInfoClick,
                browseFit = recommendationState?.browseFit,
                compact = true,
            )
        },
        onClick = onClick,
        modifier = modifier.testTag("model-row:$repositoryId"),
        emphasized = recommendationState?.personalizedResult?.category ==
            RecommendationCategory.RECOMMENDED,
        eyebrowTrailing = taskTag?.let { label ->
            {
                Surface(
                    shape = AppTheme.shapes.extraSmall,
                    color = AppTheme.colors.secondaryContainer,
                    contentColor = AppTheme.colors.onSecondaryContainer,
                ) {
                    Text(
                        text = label,
                        style = AppTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = AppTheme.spacing.spacing8, vertical = AppTheme.dimensions.size3),
                    )
                }
            }
        },
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

@Preview(name = "Search rows - compact", widthDp = 360, heightDp = 260)
@Composable
private fun SearchListItemCompactPreview() {
    SearchListItemPreviewContent()
}

@Preview(name = "Search rows - large text", widthDp = 360, heightDp = 420, fontScale = 2f)
@Composable
private fun SearchListItemLargeTextPreview() {
    SearchListItemPreviewContent()
}

@Composable
private fun SearchListItemPreviewContent() {
    CaraMLTheme(ThemePreferences()) {
        Surface {
            Column {
                SearchListItem(
                    model = ListModelsResponse.Model(
                        id = "openbmb/MiniCPM5-2B-GGUF",
                        author = "openbmb",
                        numParameters = 2_000_000_000,
                        downloads = 180_600,
                        pipelineTag = "image-text-to-text",
                    ),
                    onClick = {},
                )
                SearchListItem(
                    model = ListModelsResponse.Model(
                        id = "tencent/Hy-MT2-1.8B-GGUF",
                        author = "tencent",
                        numParameters = 1_800_000_000,
                        downloads = 375_400,
                        pipelineTag = "text-generation",
                    ),
                    onClick = {},
                )
            }
        }
    }
}
