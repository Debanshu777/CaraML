package com.debanshu777.huggingfacemanager

import com.debanshu777.huggingfacemanager.api.RemoteHuggingFaceApiService
import com.debanshu777.huggingfacemanager.repository.HuggingFaceRepository
import com.debanshu777.huggingfacemanager.usecase.GetModelDetailUseCase
import com.debanshu777.huggingfacemanager.usecase.GetModelConfigUseCase
import com.debanshu777.huggingfacemanager.usecase.GetModelFileTreeUseCase
import com.debanshu777.huggingfacemanager.usecase.ListModelsUseCase
import com.debanshu777.huggingfacemanager.usecase.ListRecommendationModelsUseCase
import com.debanshu777.huggingfacemanager.usecase.SearchModelsUseCase
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

interface HuggingFaceApi {
    val listModels: ListModelsUseCase
    val listRecommendationModels: ListRecommendationModelsUseCase
    val searchModels: SearchModelsUseCase
    val getModelDetail: GetModelDetailUseCase
    val getModelFileTree: GetModelFileTreeUseCase
    val getModelConfig: GetModelConfigUseCase
}

object HuggingFaceConstants {
    const val DEFAULT_BASE_URL = "https://huggingface.co"
}

fun createHuggingFaceApi(baseUrl: String = HuggingFaceConstants.DEFAULT_BASE_URL): HuggingFaceApi {
    val json = Json {
        prettyPrint = true
        isLenient = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    val httpClient = createPlatformHttpClient {
        followRedirects = false

        install(ContentNegotiation) {
            json(json, contentType = ContentType.Application.Json)
        }

        // Configure timeouts
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000L  // 30 seconds
            connectTimeoutMillis = 15_000L  // 15 seconds
            socketTimeoutMillis = 30_000L   // 30 seconds
        }

        install(HttpRequestRetry) {
            maxRetries = 3
            retryIf { _, response ->
                !response.status.isSuccess() && response.status.value in 500..599
            }
            retryOnServerErrors(maxRetries = 3)
            exponentialDelay()
        }
    }
    val api = RemoteHuggingFaceApiService(httpClient, json, baseUrl)
    val repository = HuggingFaceRepository(api)
    return DefaultHuggingFaceApi(
        listModels = ListModelsUseCase(repository),
        listRecommendationModels = ListRecommendationModelsUseCase(repository),
        searchModels = SearchModelsUseCase(repository),
        getModelDetail = GetModelDetailUseCase(repository),
        getModelFileTree = GetModelFileTreeUseCase(repository),
        getModelConfig = GetModelConfigUseCase(repository),
    )
}

private class DefaultHuggingFaceApi(
    override val listModels: ListModelsUseCase,
    override val listRecommendationModels: ListRecommendationModelsUseCase,
    override val searchModels: SearchModelsUseCase,
    override val getModelDetail: GetModelDetailUseCase,
    override val getModelFileTree: GetModelFileTreeUseCase,
    override val getModelConfig: GetModelConfigUseCase,
) : HuggingFaceApi
