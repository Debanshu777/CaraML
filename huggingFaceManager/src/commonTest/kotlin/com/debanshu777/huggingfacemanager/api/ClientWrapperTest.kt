package com.debanshu777.huggingfacemanager.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ClientWrapperTest {
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
}
