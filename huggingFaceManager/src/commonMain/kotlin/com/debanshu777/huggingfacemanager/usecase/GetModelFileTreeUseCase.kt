package com.debanshu777.huggingfacemanager.usecase

import com.debanshu777.huggingfacemanager.api.error.DataError
import com.debanshu777.huggingfacemanager.api.error.Result
import com.debanshu777.huggingfacemanager.model.ModelFileTreeResponse
import com.debanshu777.huggingfacemanager.model.ModelFileWeightFilter
import com.debanshu777.huggingfacemanager.model.matchesRepoFilePath
import com.debanshu777.huggingfacemanager.repository.HuggingFaceRepository

class GetModelFileTreeUseCase(private val repository: HuggingFaceRepository) {
    suspend operator fun invoke(
        modelId: String,
        weightFilter: ModelFileWeightFilter = ModelFileWeightFilter.GgufOnly,
    ): Result<List<ModelFileTreeResponse>, DataError.Network> {
        return when (val result = repository.getModelFileTree(modelId)) {
            is Result.Success -> {
                val filtered = result.data.filter {
                    it.type == "file" && weightFilter.matchesRepoFilePath(it.path)
                }
                Result.Success(filtered)
            }
            is Result.Error -> result
        }
    }
}
