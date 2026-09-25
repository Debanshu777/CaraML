package com.debanshu777.huggingfacemanager.api

import com.debanshu777.huggingfacemanager.api.error.DataError
import com.debanshu777.huggingfacemanager.api.error.Result
import io.ktor.client.HttpClient
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.util.network.UnresolvedAddressException
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlinx.io.readByteArray
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.text.CharacterCodingException

internal class ResponseLimitExceededException : SerializationException("Response item limit exceeded")

@PublishedApi
internal data class BoundedNetworkResponse<T>(
    val data: T,
    val linkHeader: String?,
)

private sealed interface BoundedTextAttempt {
    data class Complete(
        val result: Result<BoundedNetworkResponse<String>, DataError.Network>,
    ) : BoundedTextAttempt

    data class TemporaryRedirect(val location: String) : BoundedTextAttempt
}

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
        trustedSingleRedirect: ((String) -> String?)? = null,
        decode: (String) -> T,
    ): Result<T, DataError.Network> {
        return when (
            val result = networkGetBounded(
                endpoint,
                queries,
                maxResponseBytes,
                distinguishNotFound,
                trustedSingleRedirect,
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
        trustedSingleRedirect: ((String) -> String?)? = null,
        decode: (String) -> T,
    ): Result<BoundedNetworkResponse<T>, DataError.Network> {
        return when (
            val response = networkGetTextBounded(
                endpoint,
                queries,
                maxResponseBytes,
                distinguishNotFound,
                trustedSingleRedirect,
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
        trustedSingleRedirect: ((String) -> String?)? = null,
    ): Result<BoundedNetworkResponse<String>, DataError.Network> {
        require(maxResponseBytes in 1..DEFAULT_MAX_RESPONSE_BYTES) {
            "Response byte limit is outside the supported range"
        }
        val first = networkGetTextBoundedOnce(
            endpoint = endpoint,
            queries = queries,
            maxResponseBytes = maxResponseBytes,
            distinguishNotFound = distinguishNotFound,
            captureTemporaryRedirect = trustedSingleRedirect != null,
        )
        return when (first) {
            is BoundedTextAttempt.Complete -> first.result
            is BoundedTextAttempt.TemporaryRedirect -> {
                val redirectedEndpoint = try {
                    trustedSingleRedirect?.invoke(first.location)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                } ?: return Result.Error(DataError.Network.Serialization)
                when (
                    val second = networkGetTextBoundedOnce(
                        endpoint = redirectedEndpoint,
                        queries = null,
                        maxResponseBytes = maxResponseBytes,
                        distinguishNotFound = distinguishNotFound,
                        captureTemporaryRedirect = true,
                    )
                ) {
                    is BoundedTextAttempt.Complete -> second.result
                    is BoundedTextAttempt.TemporaryRedirect -> Result.Error(DataError.Network.Serialization)
                }
            }
        }
    }

    private suspend fun networkGetTextBoundedOnce(
        endpoint: String,
        queries: Map<String, String>?,
        maxResponseBytes: Long,
        distinguishNotFound: Boolean,
        captureTemporaryRedirect: Boolean,
    ): BoundedTextAttempt {
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
                                return@execute BoundedTextAttempt.Complete(
                                    Result.Error(DataError.Network.Serialization),
                                )
                            }
                            if (declaredLength != null && declaredLength > maxResponseBytes) {
                                return@execute BoundedTextAttempt.Complete(
                                    Result.Error(DataError.Network.PayloadTooLarge),
                                )
                            }
                            val bytes = response.bodyAsChannel()
                                .readRemaining(maxResponseBytes + 1L)
                                .readByteArray()
                            if (bytes.size.toLong() > maxResponseBytes) {
                                return@execute BoundedTextAttempt.Complete(
                                    Result.Error(DataError.Network.PayloadTooLarge),
                                )
                            }
                            val bodyText = bytes.decodeToString(throwOnInvalidSequence = true)
                            BoundedTextAttempt.Complete(
                                Result.Success(
                                    BoundedNetworkResponse(
                                        data = bodyText,
                                        linkHeader = response.headers[HttpHeaders.Link],
                                    ),
                                ),
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: CharacterCodingException) {
                            BoundedTextAttempt.Complete(Result.Error(DataError.Network.Serialization))
                        } catch (_: HttpRequestTimeoutException) {
                            BoundedTextAttempt.Complete(Result.Error(DataError.Network.RequestTimeout))
                        } catch (_: ConnectTimeoutException) {
                            BoundedTextAttempt.Complete(Result.Error(DataError.Network.RequestTimeout))
                        } catch (_: SocketTimeoutException) {
                            BoundedTextAttempt.Complete(Result.Error(DataError.Network.RequestTimeout))
                        } catch (_: IOException) {
                            BoundedTextAttempt.Complete(Result.Error(DataError.Network.NoInternet))
                        } catch (_: Exception) {
                            BoundedTextAttempt.Complete(Result.Error(DataError.Network.Unknown))
                        }
                    }

                    307 -> if (captureTemporaryRedirect) {
                        val locations = response.headers.getAll(HttpHeaders.Location)
                        val location = locations?.singleOrNull()
                        if (location == null || location.isEmpty() || location.length > MAX_REDIRECT_LOCATION_LENGTH ||
                            location.any { it.code < 32 || it.code == 127 }
                        ) {
                            BoundedTextAttempt.Complete(Result.Error(DataError.Network.Serialization))
                        } else {
                            BoundedTextAttempt.TemporaryRedirect(location)
                        }
                    } else {
                        BoundedTextAttempt.Complete(Result.Error(DataError.Network.Unknown))
                    }
                    in 300..399 -> BoundedTextAttempt.Complete(
                        Result.Error(
                            if (captureTemporaryRedirect) {
                                DataError.Network.Serialization
                            } else {
                                DataError.Network.Unknown
                            },
                        ),
                    )
                    401, 403 -> BoundedTextAttempt.Complete(Result.Error(DataError.Network.Unauthorized))
                    404 -> BoundedTextAttempt.Complete(
                        Result.Error(
                            if (distinguishNotFound) DataError.Network.NotFound else DataError.Network.Unknown,
                        ),
                    )
                    408 -> BoundedTextAttempt.Complete(Result.Error(DataError.Network.RequestTimeout))
                    409 -> BoundedTextAttempt.Complete(Result.Error(DataError.Network.Conflict))
                    413 -> BoundedTextAttempt.Complete(Result.Error(DataError.Network.PayloadTooLarge))
                    429 -> BoundedTextAttempt.Complete(Result.Error(DataError.Network.RateLimited))
                    in 500..599 -> BoundedTextAttempt.Complete(Result.Error(DataError.Network.ServerError))
                    else -> BoundedTextAttempt.Complete(Result.Error(DataError.Network.Unknown))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: UnresolvedAddressException) {
            BoundedTextAttempt.Complete(Result.Error(DataError.Network.NoInternet))
        } catch (_: HttpRequestTimeoutException) {
            BoundedTextAttempt.Complete(Result.Error(DataError.Network.RequestTimeout))
        } catch (_: ConnectTimeoutException) {
            BoundedTextAttempt.Complete(Result.Error(DataError.Network.RequestTimeout))
        } catch (_: SocketTimeoutException) {
            BoundedTextAttempt.Complete(Result.Error(DataError.Network.RequestTimeout))
        } catch (_: IOException) {
            BoundedTextAttempt.Complete(Result.Error(DataError.Network.NoInternet))
        } catch (_: Exception) {
            BoundedTextAttempt.Complete(Result.Error(DataError.Network.Unknown))
        }
    }

    companion object {
        const val DEFAULT_MAX_RESPONSE_BYTES: Long = 8L * 1024L * 1024L
        private const val MAX_REDIRECT_LOCATION_LENGTH: Int = 4_096
    }
}
