package com.debanshu777.huggingfacemanager.usecase

import com.debanshu777.huggingfacemanager.api.SearchModelsParams
import com.debanshu777.huggingfacemanager.api.error.DataError
import com.debanshu777.huggingfacemanager.api.error.Result
import com.debanshu777.huggingfacemanager.model.SearchModelsResponse
import com.debanshu777.huggingfacemanager.repository.HuggingFaceRepository

class SearchModelsUseCase(
    private val repository: HuggingFaceRepository
) {
    suspend operator fun invoke(params: SearchModelsParams): Result<SearchModelsResponse, DataError.Network> =
        repository.searchModels(params)
}
