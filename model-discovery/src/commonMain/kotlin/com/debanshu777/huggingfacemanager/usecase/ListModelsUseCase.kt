package com.debanshu777.huggingfacemanager.usecase

import com.debanshu777.huggingfacemanager.api.ListModelsParams
import com.debanshu777.huggingfacemanager.api.error.DataError
import com.debanshu777.huggingfacemanager.api.error.Result
import com.debanshu777.huggingfacemanager.model.ListModelsResponse
import com.debanshu777.huggingfacemanager.repository.HuggingFaceRepository

class ListModelsUseCase(
    private val repository: HuggingFaceRepository
) {
    suspend operator fun invoke(params: ListModelsParams): Result<ListModelsResponse, DataError.Network> =
        repository.listModels(params)
}
