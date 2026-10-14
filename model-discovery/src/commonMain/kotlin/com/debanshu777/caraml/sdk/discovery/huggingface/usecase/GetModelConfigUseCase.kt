package com.debanshu777.caraml.sdk.discovery.huggingface.usecase

import com.debanshu777.caraml.sdk.discovery.huggingface.api.error.DataError
import com.debanshu777.caraml.sdk.discovery.huggingface.api.error.Result
import com.debanshu777.caraml.sdk.discovery.huggingface.model.TransformerConfigResponse
import com.debanshu777.caraml.sdk.discovery.huggingface.repository.HuggingFaceRepository

class GetModelConfigUseCase(
    private val repository: HuggingFaceRepository,
) {
    suspend operator fun invoke(
        modelId: String,
        revision: String,
    ): Result<TransformerConfigResponse, DataError.Network> =
        repository.getModelConfig(modelId, revision)

    suspend fun forExactInstalledRepair(
        modelId: String,
        revision: String,
    ): Result<TransformerConfigResponse, DataError.Network> =
        repository.getExactModelConfig(modelId, revision)
}
