package com.debanshu777.caraml.features.modelhub.presentation.details

import com.debanshu777.caraml.sdk.discovery.huggingface.model.ListModelsResponse
import com.debanshu777.caraml.sdk.discovery.huggingface.model.ModelDetailResponse
import kotlinx.serialization.Serializable

/** Display-only navigation snapshot. Never supplies revision or download identity. */
@Serializable
data class ModelDetailsPreview(
    val modelId: String,
    val author: String? = null,
    val avatarUrl: String? = null,
    val task: String? = null,
    val parameters: Long? = null,
    val downloads: Int? = null,
    val likes: Int? = null,
    val transitionKey: String? = null,
) {
    fun asDisplayModel() = ModelDetailResponse(
        id = modelId, author = author, pipelineTag = task, downloads = downloads, likes = likes,
        gguf = parameters?.let { ModelDetailResponse.Gguf(total = it) },
    )
}

internal fun ListModelsResponse.Model.detailsPreview() = ModelDetailsPreview(
    modelId = id.orEmpty(), author = author, avatarUrl = authorData?.avatarUrl,
    task = pipelineTag, parameters = numParameters, downloads = downloads, likes = likes,
    transitionKey = "catalog:$id",
)
