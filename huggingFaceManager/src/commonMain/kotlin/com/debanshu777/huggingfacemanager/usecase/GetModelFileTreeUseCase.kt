package com.debanshu777.huggingfacemanager.usecase

import com.debanshu777.huggingfacemanager.api.error.DataError
import com.debanshu777.huggingfacemanager.api.error.Result
import com.debanshu777.huggingfacemanager.api.isImmutableRevision
import com.debanshu777.huggingfacemanager.model.ModelFileTreeResponse
import com.debanshu777.huggingfacemanager.model.ModelFileWeightFilter
import com.debanshu777.huggingfacemanager.model.matchesRepoFilePath
import com.debanshu777.huggingfacemanager.repository.HuggingFaceRepository

class GetModelFileTreeUseCase(private val repository: HuggingFaceRepository) {
    suspend operator fun invoke(
        modelId: String,
        revision: String,
        weightFilter: ModelFileWeightFilter = ModelFileWeightFilter.GgufOnly,
    ): Result<List<ModelFileTreeResponse>, DataError.Network> {
        if (!isImmutableRevision(revision)) return Result.Error(DataError.Network.Unknown)
        return filter(repository.getModelFileTree(modelId, revision), weightFilter)
    }

    /** Temporary compatibility adapter; remove after callers pass the detail response SHA. */
    suspend operator fun invoke(
        modelId: String,
        weightFilter: ModelFileWeightFilter = ModelFileWeightFilter.GgufOnly,
    ): Result<List<ModelFileTreeResponse>, DataError.Network> {
        val revision = when (val detail = repository.getModelDetail(modelId)) {
            is Result.Success -> detail.data.sha
            is Result.Error -> return Result.Error(detail.error)
        }
        if (revision == null || !isImmutableRevision(revision)) {
            return Result.Error(DataError.Network.Unknown)
        }
        return invoke(modelId, revision, weightFilter)
    }

    private fun filter(
        result: Result<List<ModelFileTreeResponse>, DataError.Network>,
        weightFilter: ModelFileWeightFilter,
    ): Result<List<ModelFileTreeResponse>, DataError.Network> = when (result) {
            is Result.Success -> {
                val filtered = result.data.filter {
                    it.type == "file" && weightFilter.matchesRepoFilePath(it.path)
                }
                Result.Success(filtered)
            }
            is Result.Error -> result
        }
}
