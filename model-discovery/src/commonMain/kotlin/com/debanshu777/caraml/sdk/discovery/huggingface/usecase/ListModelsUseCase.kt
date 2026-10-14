package com.debanshu777.caraml.sdk.discovery.huggingface.usecase

import com.debanshu777.caraml.sdk.discovery.huggingface.api.ListModelsParams
import com.debanshu777.caraml.sdk.discovery.huggingface.api.error.DataError
import com.debanshu777.caraml.sdk.discovery.huggingface.api.error.Result
import com.debanshu777.caraml.sdk.discovery.huggingface.model.ListModelsResponse
import com.debanshu777.caraml.sdk.discovery.huggingface.repository.HuggingFaceRepository

class ListModelsUseCase(
    private val repository: HuggingFaceRepository
) {
    suspend operator fun invoke(params: ListModelsParams): Result<ListModelsResponse, DataError.Network> =
        repository.listModels(params)
}
