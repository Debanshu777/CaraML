package com.debanshu777.huggingfacemanager.api

import com.debanshu777.huggingfacemanager.api.error.DataError
import com.debanshu777.huggingfacemanager.api.error.Result
import com.debanshu777.huggingfacemanager.model.ModelSort
import com.debanshu777.huggingfacemanager.model.ParameterRange
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelPageTest {
    @Test
    fun pageCursorPreservesQueryAndFetchesDistinctNextPage() = runTest {
        val observed = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            observed += request.url.toString()
            val first = request.url.parameters["cursor"] == null
            val headers = if (first) {
                headersOf(HttpHeaders.Link, "<${request.url}&cursor=next-opaque>; rel=\"next\"")
            } else {
                headersOf(HttpHeaders.ContentType, "application/json")
            }
            respond(if (first) "[{\"id\":\"owner/first\"}]" else "[{\"id\":\"owner/second\"}]", headers = headers)
        })
        try {
            val service = service(client)
            val request = ModelPageRequest(
                search = "Qwen & 3B?", sort = ModelSort.DOWNLOADS,
                minParams = ParameterRange.THREE_B, maxParams = ParameterRange.SIX_B,
                limit = 2,
            )
            val first = assertIs<Result.Success<ModelPage, DataError.Network>>(service.getModelPage(request)).data
            val second = assertIs<Result.Success<ModelPage, DataError.Network>>(
                service.getModelPage(request.copy(cursor = assertNotNull(first.nextCursor))),
            ).data
            assertEquals(listOf("owner/first"), first.models.map { it.id })
            assertEquals(listOf("owner/second"), second.models.map { it.id })
            assertNull(second.nextCursor)
            assertNull(first.totalCount)
            assertTrue(observed[0].contains("search=Qwen"))
            assertTrue(observed[0].contains("sort=downloads"))
            assertTrue(observed[0].contains("num_parameters=min%3A3B%2Cmax%3A6B"))
            assertTrue(observed[1].contains("cursor=next-opaque"))
        } finally { client.close() }
    }

    @Test
    fun supportedSortsUsePublicApiValues() = runTest {
        val observed = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            observed += request.url.parameters["sort"].orEmpty()
            respond("[]")
        })
        try {
            val service = service(client)
            listOf(ModelSort.TRENDING, ModelSort.DOWNLOADS, ModelSort.LIKES,
                ModelSort.CREATED, ModelSort.MODIFIED).forEach {
                assertIs<Result.Success<ModelPage, DataError.Network>>(service.getModelPage(ModelPageRequest(sort = it)))
            }
            assertEquals(listOf("trendingScore", "downloads", "likes", "createdAt", "lastModified"), observed)
            assertFailsWith<IllegalArgumentException> { ModelPageRequest(sort = ModelSort.MOST_PARAMS) }
        } finally { client.close() }
    }

    @Test
    fun rejectsForeignMalformedAndWrongQueryCursors() = runTest {
        var count = 0
        var link = ""
        val client = HttpClient(MockEngine { request ->
            count++
            respond("[]", headers = headersOf(HttpHeaders.Link, link.replace("{url}", request.url.toString())))
        })
        try {
            val service = service(client)
            val request = ModelPageRequest(search = "Qwen")
            link = "<{url}&cursor=ok>; rel=\"next\""
            val good = assertNotNull(assertIs<Result.Success<ModelPage, DataError.Network>>(
                service.getModelPage(request),
            ).data.nextCursor)
            val before = count
            assertIs<Result.Error<*, *>>(service.getModelPage(request.copy(search = "Gemma", cursor = good)))
            assertEquals(before, count)

            val invalidLinks = listOf(
                "<https://evil.example/api/models?cursor=bad>; rel=\"next\"",
                "<http://huggingface.co/api/models?cursor=bad>; rel=\"next\"",
                "<https://huggingface.co/api/other?cursor=bad>; rel=\"next\"",
                "<https://name:pass@huggingface.co/api/models?cursor=bad>; rel=\"next\"",
                "<{url}&cursor=a&cursor=b>; rel=\"next\"",
                "<{url}&cursor=ok&search=other>; rel=\"next\"",
                "<{url}&cursor=ok#fragment>; rel=\"next\"",
                "<{url}&cursor=ok>; rel=\"next\", <{url}&cursor=more>; rel=\"next\"",
                "<{url}&cursor=${"x".repeat(2_049)}>; rel=\"next\"",
            )
            for (candidate in invalidLinks) {
                link = candidate
                assertEquals(DataError.Network.Serialization,
                    assertIs<Result.Error<ModelPage, DataError.Network>>(service.getModelPage(request)).error)
            }
        } finally { client.close() }
    }

    @Test
    fun optionalMetadataAndParameterCountsStayHonest() = runTest {
        val body = """[
          {"id":"a/gguf","author":"a","pipeline_tag":"text-generation","gguf":{"total":30532122624,"totalFileSize":17310784672,"architecture":"qwen3moe","context_length":262144}},
          {"id":"b/safe","safetensors":{"parameters":{"BF16":100,"F32":25},"total":125}},
          {"id":"c/mixed","gguf":{"total":300},"safetensors":{"total":200}},
          {"id":"d/missing"},
          {"id":"e/bad","gguf":{"total":-5},"safetensors":{"parameters":{"BF16":1.5}}}
        ]""".trimIndent()
        val client = HttpClient(MockEngine { respond(body) })
        try {
            val page = assertIs<Result.Success<ModelPage, DataError.Network>>(
                service(client).getModelPage(ModelPageRequest(limit = 5)),
            ).data
            assertEquals(30_532_122_624L, page.models[0].numParameters)
            assertEquals("qwen3moe", page.models[0].architecture)
            assertEquals(262_144, page.models[0].contextLength)
            assertEquals(125L, page.models[1].numParameters)
            assertNull(page.models[2].numParameters)
            assertNull(page.models[3].numParameters)
            assertNull(page.models[4].numParameters)
        } finally { client.close() }
    }

    @Test
    fun excessRowsAndBodyAreRejected() = runTest {
        val client = HttpClient(MockEngine { request ->
            respond(if (request.url.parameters["search"] == "body") "x".repeat(600_000)
                else "[{\"id\":\"a\"},{\"id\":\"b\"}]")
        })
        try {
            val service = service(client)
            assertEquals(DataError.Network.PayloadTooLarge, assertIs<Result.Error<ModelPage, DataError.Network>>(
                service.getModelPage(ModelPageRequest(limit = 1)),
            ).error)
            assertEquals(DataError.Network.PayloadTooLarge, assertIs<Result.Error<ModelPage, DataError.Network>>(
                service.getModelPage(ModelPageRequest(search = "body")),
            ).error)
        } finally { client.close() }
    }

    private fun service(client: HttpClient) =
        RemoteHuggingFaceApiService(client, Json, "https://huggingface.co")
}
