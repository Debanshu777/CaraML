package com.debanshu777.huggingfacemanager.repository

import com.debanshu777.huggingfacemanager.api.RemoteHuggingFaceApiService
import com.debanshu777.huggingfacemanager.api.ListModelsParams
import com.debanshu777.huggingfacemanager.api.SearchModelsParams
import com.debanshu777.huggingfacemanager.api.error.DataError
import com.debanshu777.huggingfacemanager.api.error.Result
import com.debanshu777.huggingfacemanager.model.ModelDetailResponse
import com.debanshu777.huggingfacemanager.model.ListModelsResponse
import com.debanshu777.huggingfacemanager.model.ModelFileTreeResponse
import com.debanshu777.huggingfacemanager.model.SearchModelsResponse
import com.debanshu777.huggingfacemanager.model.TransformerConfigResponse

class HuggingFaceRepository(
    private val api: RemoteHuggingFaceApiService
) {
    suspend fun listModels(params: ListModelsParams): Result<ListModelsResponse, DataError.Network> =
        api.listModels(params)

    suspend fun listRecommendationModels(
        params: ListModelsParams,
    ): Result<ListModelsResponse, DataError.Network> = api.listRecommendationModels(params)

    suspend fun searchModels(params: SearchModelsParams): Result<SearchModelsResponse, DataError.Network> =
        api.searchModels(params)

    suspend fun getModelDetail(modelId: String): Result<ModelDetailResponse, DataError.Network> =
        api.getModelDetail(modelId)

    suspend fun getRecommendationModelDetail(
        modelId: String,
    ): Result<ModelDetailResponse, DataError.Network> = api.getRecommendationModelDetail(modelId)

    suspend fun getRecommendationModelDetail(
        modelId: String,
        revision: String,
    ): Result<ModelDetailResponse, DataError.Network> = api.getRecommendationModelDetail(modelId, revision)

    suspend fun getModelFileTree(
        modelId: String,
        revision: String,
    ): Result<List<ModelFileTreeResponse>, DataError.Network> =
        api.getModelFileTree(modelId, revision)

    suspend fun getModelConfig(
        modelId: String,
        revision: String,
    ): Result<TransformerConfigResponse, DataError.Network> =
        api.getModelConfig(modelId, revision)
}
