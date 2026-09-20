package com.debanshu777.huggingfacemanager.api

import com.debanshu777.huggingfacemanager.api.error.DataError
import com.debanshu777.huggingfacemanager.api.error.Result
import io.ktor.client.HttpClient
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.util.network.UnresolvedAddressException
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

internal class ResponseLimitExceededException : SerializationException("Response item limit exceeded")

@PublishedApi
internal data class BoundedNetworkResponse<T>(
    val data: T,
    val linkHeader: String?,
)

class ClientWrapper(
    networkClient: HttpClient,
    @PublishedApi internal val json: Json,
) {
    @PublishedApi
    internal val networkClient = networkClient.config {
        followRedirects = false
    }

    suspend inline fun <reified T> networkGetUsecase(
        endpoint: String,
        queries: Map<String, String>? = null,
        maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
    ): Result<T, DataError.Network> {
        return when (val result = networkGetBounded<T>(endpoint, queries, maxResponseBytes)) {
            is Result.Success -> Result.Success(result.data.data)
            is Result.Error -> Result.Error(result.error)
        }
    }

    internal suspend fun <T> networkGetUsecase(
        endpoint: String,
        queries: Map<String, String>? = null,
        maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
        distinguishNotFound: Boolean = false,
        decode: (String) -> T,
    ): Result<T, DataError.Network> {
        return when (
            val result = networkGetBounded(
                endpoint,
                queries,
                maxResponseBytes,
                distinguishNotFound,
                decode,
            )
        ) {
            is Result.Success -> Result.Success(result.data.data)
            is Result.Error -> Result.Error(result.error)
        }
    }

    @PublishedApi
    internal suspend inline fun <reified T> networkGetBounded(
        endpoint: String,
        queries: Map<String, String>? = null,
        maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
    ): Result<BoundedNetworkResponse<T>, DataError.Network> = networkGetBounded(
        endpoint = endpoint,
        queries = queries,
        maxResponseBytes = maxResponseBytes,
        decode = { bodyText -> json.decodeFromString<T>(bodyText) },
    )

    @PublishedApi
    internal suspend fun <T> networkGetBounded(
        endpoint: String,
        queries: Map<String, String>? = null,
        maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
        distinguishNotFound: Boolean = false,
        decode: (String) -> T,
    ): Result<BoundedNetworkResponse<T>, DataError.Network> {
        return when (
            val response = networkGetTextBounded(
                endpoint,
                queries,
                maxResponseBytes,
                distinguishNotFound,
            )
        ) {
            is Result.Error -> Result.Error(response.error)
            is Result.Success -> try {
                Result.Success(
                    BoundedNetworkResponse(
                        data = decode(response.data.data),
                        linkHeader = response.data.linkHeader,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: ResponseLimitExceededException) {
                Result.Error(DataError.Network.PayloadTooLarge)
            } catch (_: SerializationException) {
                Result.Error(DataError.Network.Serialization)
            } catch (_: Exception) {
                Result.Error(DataError.Network.Unknown)
            }
        }
    }

    private suspend fun networkGetTextBounded(
        endpoint: String,
        queries: Map<String, String>?,
        maxResponseBytes: Long,
        distinguishNotFound: Boolean = false,
    ): Result<BoundedNetworkResponse<String>, DataError.Network> {
        require(maxResponseBytes in 1..DEFAULT_MAX_RESPONSE_BYTES) {
            "Response byte limit is outside the supported range"
        }
        return try {
            networkClient.prepareGet(endpoint) {
                queries?.forEach { (key, value) ->
                    parameter(key, value)
                }
            }.execute { response ->
                when (response.status.value) {
                    in 200..299 -> {
                        try {
                            val contentLengthHeader = response.headers[HttpHeaders.ContentLength]
                            val declaredLength = contentLengthHeader?.toLongOrNull()
                            if (contentLengthHeader != null &&
                                (declaredLength == null || declaredLength < 0L)
                            ) {
                                return@execute Result.Error(DataError.Network.Serialization)
                            }
                            if (declaredLength != null && declaredLength > maxResponseBytes) {
                                return@execute Result.Error(DataError.Network.PayloadTooLarge)
                            }
                            val bytes = response.bodyAsChannel()
                                .readRemaining(maxResponseBytes + 1L)
                                .readByteArray()
                            if (bytes.size.toLong() > maxResponseBytes) {
                                return@execute Result.Error(DataError.Network.PayloadTooLarge)
                            }
                            val bodyText = bytes.decodeToString(throwOnInvalidSequence = true)
                            Result.Success(
                                BoundedNetworkResponse(
                                    data = bodyText,
                                    linkHeader = response.headers[HttpHeaders.Link],
                                ),
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            Result.Error(DataError.Network.Unknown)
                        }
                    }

                    401 -> Result.Error(DataError.Network.Unauthorized)
                    404 -> Result.Error(
                        if (distinguishNotFound) DataError.Network.NotFound else DataError.Network.Unknown,
                    )
                    408 -> Result.Error(DataError.Network.RequestTimeout)
                    409 -> Result.Error(DataError.Network.Conflict)
                    413 -> Result.Error(DataError.Network.PayloadTooLarge)
                    in 500..599 -> Result.Error(DataError.Network.ServerError)
                    else -> Result.Error(DataError.Network.Unknown)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: UnresolvedAddressException) {
            Result.Error(DataError.Network.NoInternet)
        } catch (_: Exception) {
            Result.Error(DataError.Network.Unknown)
        }
    }

    companion object {
        const val DEFAULT_MAX_RESPONSE_BYTES: Long = 8L * 1024L * 1024L
    }
}
