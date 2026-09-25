package com.debanshu777.huggingfacemanager.api

import com.debanshu777.huggingfacemanager.api.error.DataError
import com.debanshu777.huggingfacemanager.api.error.Result
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ClientWrapperTest {
    @Test
    fun optionalRequestsCanDistinguishNotFoundWithoutChangingTheDefault() = runTest {
        val client = HttpClient(MockEngine { respond("", status = HttpStatusCode.NotFound) })
        val wrapper = ClientWrapper(client, Json)

        try {
            assertEquals(
                Result.Error(DataError.Network.NotFound),
                wrapper.networkGetUsecase(
                    endpoint = "https://example.invalid",
                    distinguishNotFound = true,
                    decode = { emptyMap<String, String>() },
                ),
            )
            assertEquals(
                Result.Error(DataError.Network.Unknown),
                wrapper.networkGetUsecase<Map<String, String>>("https://example.invalid"),
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun cancellationIsNeverConvertedIntoAnUnknownNetworkError() = runTest {
        val client = HttpClient(MockEngine { throw CancellationException("cancelled") })
        val wrapper = ClientWrapper(client, Json)

        try {
            assertFailsWith<CancellationException> {
                wrapper.networkGetUsecase<Map<String, String>>("https://example.invalid")
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun rateLimitStatusHasAnExplicitTransientCategory() = runTest {
        val client = HttpClient(MockEngine { respond("", status = HttpStatusCode.TooManyRequests) })
        val wrapper = ClientWrapper(client, Json)

        try {
            assertEquals(
                Result.Error(DataError.Network.RateLimited),
                wrapper.networkGetUsecase<Map<String, String>>("https://huggingface.co"),
            )
        } finally {
            client.close()
        }
    }
}
