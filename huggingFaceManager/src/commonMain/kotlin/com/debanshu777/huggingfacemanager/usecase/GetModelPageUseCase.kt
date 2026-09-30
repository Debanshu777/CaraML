package com.debanshu777.huggingfacemanager.usecase

import com.debanshu777.huggingfacemanager.api.ModelPage
import com.debanshu777.huggingfacemanager.api.ModelPageRequest
import com.debanshu777.huggingfacemanager.api.error.DataError
import com.debanshu777.huggingfacemanager.api.error.Result
import com.debanshu777.huggingfacemanager.repository.HuggingFaceRepository

class GetModelPageUseCase(private val repository: HuggingFaceRepository) {
    suspend operator fun invoke(params: ModelPageRequest): Result<ModelPage, DataError.Network> =
        repository.getModelPage(params)
}
