package com.debanshu777.huggingfacemanager.usecase

import com.debanshu777.huggingfacemanager.api.error.DataError
import com.debanshu777.huggingfacemanager.api.error.Result
import com.debanshu777.huggingfacemanager.model.TransformerConfigResponse
import com.debanshu777.huggingfacemanager.repository.HuggingFaceRepository

class GetModelConfigUseCase(
    private val repository: HuggingFaceRepository,
) {
    suspend operator fun invoke(
        modelId: String,
        revision: String,
    ): Result<TransformerConfigResponse, DataError.Network> =
        repository.getModelConfig(modelId, revision)
}
