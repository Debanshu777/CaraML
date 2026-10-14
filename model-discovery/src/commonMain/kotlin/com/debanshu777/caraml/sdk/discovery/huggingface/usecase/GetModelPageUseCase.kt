package com.debanshu777.caraml.sdk.discovery.huggingface.usecase

import com.debanshu777.caraml.sdk.discovery.huggingface.api.ModelPage
import com.debanshu777.caraml.sdk.discovery.huggingface.api.ModelPageRequest
import com.debanshu777.caraml.sdk.discovery.huggingface.api.error.DataError
import com.debanshu777.caraml.sdk.discovery.huggingface.api.error.Result
import com.debanshu777.caraml.sdk.discovery.huggingface.repository.HuggingFaceRepository

class GetModelPageUseCase(private val repository: HuggingFaceRepository) {
    suspend operator fun invoke(params: ModelPageRequest): Result<ModelPage, DataError.Network> =
        repository.getModelPage(params)
}
