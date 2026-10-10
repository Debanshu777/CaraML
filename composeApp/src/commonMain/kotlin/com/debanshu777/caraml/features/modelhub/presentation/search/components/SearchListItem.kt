package com.debanshu777.caraml.features.modelhub.presentation.search.components

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.core.recommendation.RecommendationCategory
import com.debanshu777.caraml.core.theme.CaraMLTheme
import com.debanshu777.caraml.core.theme.ThemePreferences
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
    ModelHubCard(
        title = repositoryId.substringAfter('/', missingDelimiterValue = repositoryId),
        eyebrow = model.author?.takeIf(String::isNotBlank)
            ?: repositoryId.substringBefore('/', missingDelimiterValue = "").takeIf(String::isNotBlank),
        task = model.pipelineTag,
        metadata = listOfNotNull(
            model.numParameters?.let { "${formatCompactMetric(it)} parameters" },
            model.downloads?.let { "${formatCompactMetric(it.toLong())} downloads" },
        ).joinToString(" · "),
        status = {
            ModelRecommendationStatus(
                state = recommendationState?.descriptorState ?: DescriptorState.NEEDS_INFORMATION,
                recommendation = recommendationState?.personalizedResult,
                onInfoClick = onRecommendationInfoClick,
                browseFit = recommendationState?.browseFit,
                compact = false,
                plain = true,
            )
        },
        modifier = modifier
            .testTag("model-row:$repositoryId")
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "Open model $repositoryId" },
        action = {
            ModelHubAction("View model", onClick = onClick)
        },
        badge = taskTag?.let { label ->
            {
                Text(
                    text = label,
                    style = AppTheme.typography.labelSmall,
                    color = AppTheme.colors.onSurface,
                )
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

@Preview(name = "Search rows - unknown fit", widthDp = 360, heightDp = 480)
@Composable
private fun SearchListItemCompactPreview() {
    SearchListItemPreviewContent()
}

@Preview(name = "Search rows - large text", widthDp = 360, heightDp = 820, fontScale = 2f)
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
