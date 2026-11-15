package com.debanshu777.caraml.sdk.discovery.huggingface.usecase

import com.debanshu777.caraml.sdk.discovery.huggingface.model.ModelCardDocument

import com.debanshu777.caraml.sdk.discovery.huggingface.api.error.DataError
import com.debanshu777.caraml.sdk.discovery.huggingface.api.error.Result
import com.debanshu777.caraml.sdk.discovery.huggingface.repository.HuggingFaceRepository

class GetModelCardUseCase(private val repository: HuggingFaceRepository) {
    suspend operator fun invoke(modelId: String, revision: String): Result<ModelCardDocument, DataError.Network> =
        repository.getModelCard(modelId, revision)
}
