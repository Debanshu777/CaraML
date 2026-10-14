package com.debanshu777.caraml.sdk.discovery.huggingface.usecase

import com.debanshu777.caraml.sdk.discovery.huggingface.api.SearchModelsParams
import com.debanshu777.caraml.sdk.discovery.huggingface.api.error.DataError
import com.debanshu777.caraml.sdk.discovery.huggingface.api.error.Result
import com.debanshu777.caraml.sdk.discovery.huggingface.model.SearchModelsResponse
import com.debanshu777.caraml.sdk.discovery.huggingface.repository.HuggingFaceRepository

class SearchModelsUseCase(
    private val repository: HuggingFaceRepository
) {
    suspend operator fun invoke(params: SearchModelsParams): Result<SearchModelsResponse, DataError.Network> =
        repository.searchModels(params)
}
