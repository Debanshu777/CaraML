package com.debanshu777.caraml.sdk.discovery.huggingface.api

import com.debanshu777.caraml.sdk.discovery.huggingface.api.error.DataError
import com.debanshu777.caraml.sdk.discovery.huggingface.api.error.Result
import com.debanshu777.caraml.sdk.discovery.huggingface.model.ModelSort
import com.debanshu777.caraml.sdk.discovery.huggingface.model.ParameterRange
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Url
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class ModelPageTest {
    @Test
    fun pageContinuationPreservesQueryAndUsesServerCount() = runTest {
        val observed = mutableListOf<Url>()
        val client = HttpClient(MockEngine { request ->
            observed += request.url
            val page = request.url.parameters["p"]!!.toInt()
            respond(envelope("""[{"id":"owner/page$page"}]""", page, 30, if (page == 0) 31 else 30))
        })
        try {
            val service = service(client)
            val request = ModelPageRequest(search = "Qwen & 3B?/模型", sort = ModelSort.DOWNLOADS,
                minParams = ParameterRange.THREE_B, maxParams = ParameterRange.SIX_B,
                pipelineTag = "text-generation")
            val first = success(service.getModelPage(request))
            val second = success(service.getModelPage(request.copy(cursor = assertNotNull(first.nextCursor))))
            assertEquals(listOf("owner/page0"), first.models.map { it.id })
            assertEquals(listOf("owner/page1"), second.models.map { it.id })
            assertNull(second.nextCursor)
            assertEquals(31, first.totalCount)
            assertEquals(30, second.totalCount)
            assertEquals("/models-json", observed[0].encodedPath)
            assertEquals(request.search, observed[0].parameters["search"])
            assertEquals("gguf", observed[0].parameters["library"])
            assertEquals("llama.cpp", observed[0].parameters["apps"])
            assertEquals("text-generation", observed[0].parameters["pipeline_tag"])
            assertEquals("min:3B,max:6B", observed[0].parameters["num_parameters"])
            assertEquals("true", observed[0].parameters["withCount"])
            assertEquals("1", observed[1].parameters["p"])
            listOf("limit", "cursor", "expand", "direction", "filter").forEach {
                assertNull(observed[0].parameters[it])
            }
            listOf(request.copy(search = "other"), request.copy(sort = ModelSort.LIKES),
                request.copy(filter = listOf("onnx")), request.copy(apps = emptyList()),
                request.copy(pipelineTag = "other"), request.copy(maxParams = null)).forEach {
                assertIs<Result.Error<*, *>>(service.getModelPage(it.copy(cursor = first.nextCursor)))
            }
            assertEquals(2, observed.size)
        } finally { client.close() }
    }

    @Test
    fun supportedSortsAndOptionalFiltersUseWebsiteValues() = runTest {
        val observed = mutableListOf<Url>()
        val client = HttpClient(MockEngine { request -> observed += request.url; respond(envelope()) })
        try {
            val service = service(client)
            listOf(ModelSort.TRENDING, ModelSort.DOWNLOADS, ModelSort.LIKES, ModelSort.CREATED, ModelSort.MODIFIED).forEach {
                success(service.getModelPage(ModelPageRequest(sort = it, filter = emptyList(), apps = emptyList())))
            }
            assertEquals(listOf("trending", "downloads", "likes", "created", "modified"), observed.map { it.parameters["sort"] })
            listOf("search", "library", "apps", "num_parameters", "pipeline_tag").forEach { assertNull(observed[0].parameters[it]) }
            assertFailsWith<IllegalArgumentException> { ModelPageRequest(sort = ModelSort.MOST_PARAMS) }
        } finally { client.close() }
    }

    @Test
    fun normalizesAuthorGatingAndUnknownParameterCount() = runTest {
        val body = envelope("""[
            {"id":"a/one","numParameters":0,"gated":false,"authorData":{"avatarUrl":"/avatars/a.svg","fullname":"A"},"newServerField":{}},
            {"id":"a/two","numParameters":30532122624,"gated":"auto","authorData":{"avatarUrl":"https://www.gravatar.com/avatar/a?d=retro"}},
            {"id":"a/three","numParameters":-1,"gated":"manual","authorData":{"avatarUrl":"javascript:alert(1)"}},
            {"id":"a/four","numParameters":1.5,"authorData":{"avatarUrl":"//evil.example/a.png"}},
            {"id":"a/five","authorData":{"avatarUrl":"https://user:pass@example.com/a"}}
        ]""", total = 5)
        val client = HttpClient(MockEngine { respond(body) })
        try {
            val models = success(service(client).getModelPage(ModelPageRequest())).models
            assertNull(models[0].numParameters)
            assertEquals("false", models[0].gated)
            assertEquals("https://huggingface.co/avatars/a.svg", models[0].authorData?.avatarUrl)
            assertEquals("A", models[0].authorData?.fullname)
            assertEquals(30_532_122_624L, models[1].numParameters)
            assertEquals("auto", models[1].gated)
            assertNotNull(models[1].authorData?.avatarUrl)
            assertEquals("manual", models[2].gated)
            models.drop(2).forEach { assertNull(it.numParameters); assertNull(it.authorData?.avatarUrl) }
            assertNull(models[0].createdAt)
            assertNull(models[0].tags)
        } finally { client.close() }
    }

    @Test
    fun invalidEnvelopesFailAndEmptyCatalogSucceeds() = runTest {
        var body = envelope()
        val client = HttpClient(MockEngine { respond(body) })
        try {
            val service = service(client)
            assertNull(success(service.getModelPage(ModelPageRequest())).nextCursor)
            listOf("[]", "{}", envelope(page = 1), envelope(size = 0), envelope(size = 101),
                envelope(total = -1), envelope(total = 1),
                envelope("""[{"id":"a"}]""", total = 0),
                envelope("""[{"id":"a"},{"id":"b"}]""", total = 1),
                envelope().replace("\"numTotalItems\":0", "\"numTotalItems\":\"0\""),
                envelope("""[{"id":"../invalid"}]""", total = 1)).forEach {
                body = it
                assertEquals(DataError.Network.Serialization, assertIs<Result.Error<ModelPage, DataError.Network>>(service.getModelPage(ModelPageRequest())).error)
            }
            body = envelope("""[{"id":"a"},{"id":"b"}]""", size = 1, total = 2)
            assertEquals(DataError.Network.PayloadTooLarge, assertIs<Result.Error<ModelPage, DataError.Network>>(service.getModelPage(ModelPageRequest())).error)
            body = "x".repeat(600_000)
            assertEquals(DataError.Network.PayloadTooLarge, assertIs<Result.Error<ModelPage, DataError.Network>>(service.getModelPage(ModelPageRequest())).error)
        } finally { client.close() }
    }

    @Test
    fun continuationBindsServerPageSizeAndRefreshAcceptsNewSize() = runTest {
        var size = 20
        val client = HttpClient(MockEngine { request ->
            val page = request.url.parameters["p"]!!.toInt()
            respond(envelope("""[{"id":"owner/page$page"}]""", page, size, 100))
        })
        try {
            val service = service(client)
            val request = ModelPageRequest()
            val first = success(service.getModelPage(request))
            assertEquals(20, first.pageSize)
            val cursor = assertNotNull(first.nextCursor)
            assertEquals(20, cursor.expectedPageSize)
            size = 30
            assertEquals(DataError.Network.Serialization,
                assertIs<Result.Error<ModelPage, DataError.Network>>(
                    service.getModelPage(request.copy(cursor = cursor)),
                ).error)
            val refreshed = success(service.getModelPage(request))
            assertEquals(30, refreshed.pageSize)
            assertEquals(30, assertNotNull(refreshed.nextCursor).expectedPageSize)
            assertEquals(1, success(service.getModelPage(request.copy(cursor = refreshed.nextCursor))).pageIndex)
        } finally { client.close() }
    }

    private fun envelope(rows: String = "[]", page: Int = 0, size: Int = 30, total: Int = 0) =
        """{"models":$rows,"pageIndex":$page,"numItemsPerPage":$size,"numTotalItems":$total}"""
    private fun success(result: Result<ModelPage, DataError.Network>) =
        assertIs<Result.Success<ModelPage, DataError.Network>>(result).data
    private fun service(client: HttpClient) = RemoteHuggingFaceApiService(client, Json, "https://huggingface.co")
}
