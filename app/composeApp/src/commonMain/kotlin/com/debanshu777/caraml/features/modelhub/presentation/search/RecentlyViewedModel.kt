package com.debanshu777.caraml.features.modelhub.presentation.search

import com.debanshu777.caraml.sdk.discovery.huggingface.model.ModelDetailResponse
import com.debanshu777.caraml.core.data.settings.RecentModelKind
import com.debanshu777.caraml.core.data.settings.RecentlyViewedModel
import com.debanshu777.caraml.features.modelhub.presentation.details.ModelDetailsPreview

internal fun ModelDetailResponse.recentlyViewed(mode: ModelHubBrowseMode) = RecentlyViewedModel(
    mode = when (mode) {
        ModelHubBrowseMode.LanguageModels -> RecentModelKind.Text
        ModelHubBrowseMode.DiffusionImage -> RecentModelKind.Image
        ModelHubBrowseMode.DiffusionVideo -> RecentModelKind.Video
    },
    modelId = modelId?.takeIf(String::isNotBlank) ?: id.orEmpty(),
    author = author,
    task = pipelineTag,
    parameters = gguf?.total ?: safetensors?.total,
    downloads = downloads,
    likes = likes,
)

internal fun RecentlyViewedModel.browseMode(): ModelHubBrowseMode = when (mode) {
    RecentModelKind.Text -> ModelHubBrowseMode.LanguageModels
    RecentModelKind.Image -> ModelHubBrowseMode.DiffusionImage
    RecentModelKind.Video -> ModelHubBrowseMode.DiffusionVideo
}

internal fun RecentlyViewedModel.detailsPreview() = ModelDetailsPreview(
    modelId = modelId, author = author, task = task, parameters = parameters,
    downloads = downloads, likes = likes, transitionKey = "recent:$identity",
)

fun recentlyViewedModelsForDisplay(models: List<RecentlyViewedModel>): List<RecentlyViewedModel> = models.take(4)
