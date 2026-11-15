package com.debanshu777.caraml.sdk.discovery.huggingface

import com.debanshu777.caraml.sdk.discovery.huggingface.api.RemoteHuggingFaceApiService
import com.debanshu777.caraml.sdk.discovery.huggingface.repository.HuggingFaceRepository
import com.debanshu777.caraml.sdk.discovery.huggingface.usecase.GetModelDetailUseCase
import com.debanshu777.caraml.sdk.discovery.huggingface.usecase.GetModelCardUseCase
import com.debanshu777.caraml.sdk.discovery.huggingface.usecase.GetModelConfigUseCase
import com.debanshu777.caraml.sdk.discovery.huggingface.usecase.GetModelFileTreeUseCase
import com.debanshu777.caraml.sdk.discovery.huggingface.usecase.GetModelPageUseCase
import com.debanshu777.caraml.sdk.discovery.huggingface.usecase.GetRecommendationModelDetailUseCase
import com.debanshu777.caraml.sdk.discovery.huggingface.usecase.ListModelsUseCase
import com.debanshu777.caraml.sdk.discovery.huggingface.usecase.ListRecommendationModelsUseCase
import com.debanshu777.caraml.sdk.discovery.huggingface.usecase.SearchModelsUseCase
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.accept
import io.ktor.http.ContentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json

interface HuggingFaceApi : AutoCloseable {
    /** Factory-created facades own their HTTP clients. Custom resource-free facades may use this default. */
    override fun close() = Unit
    val getModelPage: GetModelPageUseCase
    val listModels: ListModelsUseCase
    val listRecommendationModels: ListRecommendationModelsUseCase
    val searchModels: SearchModelsUseCase
    val getModelDetail: GetModelDetailUseCase
    val getModelCard: GetModelCardUseCase
    val getRecommendationModelDetail: GetRecommendationModelDetailUseCase
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

        defaultRequest { accept(ContentType.Application.Json) }

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
    return createOwnedHuggingFaceApi(httpClient, json, baseUrl)
}

/** Takes ownership even if facade construction fails, so platform clients cannot escape unclosed. */
internal fun createOwnedHuggingFaceApi(httpClient: HttpClient, json: Json, baseUrl: String): HuggingFaceApi {
    val api = try {
        RemoteHuggingFaceApiService(httpClient, json, baseUrl)
    } catch (failure: Throwable) {
        httpClient.close()
        throw failure
    }
    val repository = HuggingFaceRepository(api)
    return DefaultHuggingFaceApi(
        getModelPage = GetModelPageUseCase(repository),
        listModels = ListModelsUseCase(repository),
        listRecommendationModels = ListRecommendationModelsUseCase(repository),
        searchModels = SearchModelsUseCase(repository),
        getModelDetail = GetModelDetailUseCase(repository),
        getModelCard = GetModelCardUseCase(repository),
        getRecommendationModelDetail = GetRecommendationModelDetailUseCase(repository),
        getModelFileTree = GetModelFileTreeUseCase(repository),
        getModelConfig = GetModelConfigUseCase(repository),
        closeClients = {
            try { api.close() } finally { httpClient.close() }
        },
    )
}

private class DefaultHuggingFaceApi(
    override val getModelPage: GetModelPageUseCase,
    override val listModels: ListModelsUseCase,
    override val listRecommendationModels: ListRecommendationModelsUseCase,
    override val searchModels: SearchModelsUseCase,
    override val getModelDetail: GetModelDetailUseCase,
    override val getModelCard: GetModelCardUseCase,
    override val getRecommendationModelDetail: GetRecommendationModelDetailUseCase,
    override val getModelFileTree: GetModelFileTreeUseCase,
    override val getModelConfig: GetModelConfigUseCase,
    private val closeClients: () -> Unit,
) : HuggingFaceApi {
    override fun close() = closeClients()
}
