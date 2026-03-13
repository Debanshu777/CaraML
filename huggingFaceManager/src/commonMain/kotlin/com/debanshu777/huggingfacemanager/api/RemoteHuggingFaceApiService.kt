package com.debanshu777.huggingfacemanager.api

import com.debanshu777.huggingfacemanager.api.error.DataError
import com.debanshu777.huggingfacemanager.api.error.Result
import com.debanshu777.huggingfacemanager.model.ModelDetailResponse
import com.debanshu777.huggingfacemanager.model.ListModelsResponse
import com.debanshu777.huggingfacemanager.model.ModelFileTreeResponse
import com.debanshu777.huggingfacemanager.model.SearchModelsResponse
import io.ktor.client.HttpClient
import io.ktor.http.URLBuilder
import io.ktor.http.encodedPath
import kotlinx.serialization.json.Json

class RemoteHuggingFaceApiService(
    client: HttpClient,
    json: Json,
    private val baseUrl: String
) {
    private val clientWrapper = ClientWrapper(client, json)

    suspend fun listModels(params: ListModelsParams): Result<ListModelsResponse, DataError.Network> {
        val url = URLBuilder(baseUrl).apply {
            encodedPath = "models-json"
            parameters.apply {
                append(
                    "num_parameters",
                    "min:${params.minParams.apiValue},max:${params.maxParams.apiValue}"
                )
                append("library", params.library.joinToString(","))
                append("apps", params.apps.joinToString(","))
                append("sort", params.sort.apiValue)
                append("withCount", params.withCount.toString())
                append("p", params.page.toString())
            }
        }.build()

        return clientWrapper.networkGetUsecase(
            endpoint = url.toString(),
            queries = null
        )
    }

    suspend fun searchModels(params: SearchModelsParams): Result<SearchModelsResponse, DataError.Network> {
        val url = URLBuilder(baseUrl).apply {
            encodedPath = "api/quicksearch"
            parameters.apply {
                append("q", params.query)
                append("type", "model")
                append("limit", params.limit.toString())
            }
        }.build()

        return clientWrapper.networkGetUsecase(
            endpoint = url.toString(),
            queries = null
        )
    }

    suspend fun getModelDetail(modelId: String): Result<ModelDetailResponse, DataError.Network> {
        require(modelId.isNotBlank()) { "modelId must not be blank" }
        val url = URLBuilder(baseUrl).apply {
            encodedPath = "api/models/${modelId.trim()}"
        }.build()

        return clientWrapper.networkGetUsecase(
            endpoint = url.toString(),
            queries = null
        )
    }

    suspend fun getModelFileTree(modelId: String): Result<List<ModelFileTreeResponse>, DataError.Network> {
        require(modelId.isNotBlank()) { "modelId must not be blank" }
        val url = URLBuilder(baseUrl).apply {
            encodedPath = "api/models/${modelId.trim()}/tree/main"
            parameters.append("recursive", "true")
        }.build()
        return clientWrapper.networkGetUsecase<List<ModelFileTreeResponse>>(
            endpoint = url.toString(),
            queries = null
        )
    }
}
