package com.debanshu777.huggingfacemanager.usecase

import com.debanshu777.huggingfacemanager.api.error.DataError
import com.debanshu777.huggingfacemanager.api.error.Result
import com.debanshu777.huggingfacemanager.model.ModelDetailResponse
import com.debanshu777.huggingfacemanager.repository.HuggingFaceRepository

class GetModelDetailUseCase(
    private val repository: HuggingFaceRepository
) {
    suspend operator fun invoke(modelId: String): Result<ModelDetailResponse, DataError.Network> =
        repository.getModelDetail(modelId)
}
