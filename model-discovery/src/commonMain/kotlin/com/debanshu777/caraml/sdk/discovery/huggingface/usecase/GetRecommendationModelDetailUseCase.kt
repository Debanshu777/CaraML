package com.debanshu777.caraml.sdk.discovery.huggingface.usecase

import com.debanshu777.caraml.sdk.discovery.huggingface.api.error.DataError
import com.debanshu777.caraml.sdk.discovery.huggingface.api.error.Result
import com.debanshu777.caraml.sdk.discovery.huggingface.model.ModelDetailResponse
import com.debanshu777.caraml.sdk.discovery.huggingface.repository.HuggingFaceRepository

class GetRecommendationModelDetailUseCase(
    private val repository: HuggingFaceRepository,
) {
    suspend operator fun invoke(
        modelId: String,
    ): Result<ModelDetailResponse, DataError.Network> = repository.getRecommendationModelDetail(modelId)

    suspend operator fun invoke(
        modelId: String,
        revision: String,
    ): Result<ModelDetailResponse, DataError.Network> =
        repository.getRecommendationModelDetail(modelId, revision)
}
