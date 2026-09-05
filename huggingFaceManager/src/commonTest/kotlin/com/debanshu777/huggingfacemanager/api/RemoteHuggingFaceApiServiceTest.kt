package com.debanshu777.huggingfacemanager.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class RemoteHuggingFaceApiServiceTest {
    @Test
    fun modelTreeBuildsPathFromValidatedSegments() = runTest {
        var observedPath: String? = null
        val client = HttpClient(MockEngine { request ->
            observedPath = request.url.encodedPath
            respond(
                content = "[]",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        try {
            val service = RemoteHuggingFaceApiService(client, Json, "https://huggingface.co")
            service.getModelFileTree("owner/model", REVISION)
            assertEquals("/api/models/owner/model/tree/$REVISION", observedPath)
        } finally {
            client.close()
        }
    }

    @Test
    fun unsafeModelIdIsRejectedBeforeAnyRequest() = runTest {
        var requests = 0
        val client = HttpClient(MockEngine {
            requests++
            respond("[]")
        })
        try {
            val service = RemoteHuggingFaceApiService(client, Json, "https://huggingface.co")
            service.getModelFileTree("owner/model?recursive=false", REVISION)
            assertEquals(0, requests)
        } finally {
            client.close()
        }
    }

    private companion object {
        const val REVISION = "0123456789abcdef0123456789abcdef01234567"
    }
}
