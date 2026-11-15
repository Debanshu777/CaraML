package com.debanshu777.caraml.sdk.discovery.huggingface.api

import com.debanshu777.caraml.sdk.discovery.huggingface.createOwnedHuggingFaceApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HttpClientOwnershipTest {
    @Test
    fun closingWrapperReleasesItsClientAndKeepsTheHostUsable() = runTest {
        val host = HttpClient(MockEngine { respond("{}") })
        val wrapper = ClientWrapper(host, Json)
        try {
            wrapper.close()
            wrapper.close()
            wrapper.networkClient.coroutineContext[Job]!!.join()
            assertTrue(wrapper.networkClient.coroutineContext[Job]!!.isCompleted)
            assertEquals(200, host.get("https://huggingface.co/health").status.value)
        } finally {
            host.close()
        }
    }

    @Test
    fun closingServiceDoesNotCloseItsBorrowedHost() = runTest {
        val host = HttpClient(MockEngine { respond("{}") })
        try {
            val service = RemoteHuggingFaceApiService(host, Json, "https://huggingface.co")
            service.close()
            service.close()
            assertEquals(200, host.get("https://huggingface.co/health").status.value)
        } finally {
            host.close()
        }
    }

    @Test
    fun closingOwnedFacadeReleasesThePlatformClient() = runTest {
        val host = HttpClient(MockEngine) { engine { addHandler { respond("{}") } } }
        val engineJob = host.engine.coroutineContext[Job]!!
        val facade = createOwnedHuggingFaceApi(host, Json, "https://huggingface.co")
        facade.close()
        facade.close()
        withTimeout(5_000) {
            host.coroutineContext[Job]!!.join()
            // A derived client retains the managed engine even after its parent closes.
            engineJob.join()
        }
        assertTrue(host.coroutineContext[Job]!!.isCompleted)
        assertTrue(engineJob.isCompleted)
    }

    @Test
    fun failedFacadeConstructionAlsoReleasesItsOwnedClient() = runTest {
        val host = HttpClient(MockEngine { respond("{}") })
        assertFailsWith<IllegalArgumentException> {
            createOwnedHuggingFaceApi(host, Json, "http://example.test")
        }
        host.coroutineContext[Job]!!.join()
        assertTrue(host.coroutineContext[Job]!!.isCompleted)
    }
}
