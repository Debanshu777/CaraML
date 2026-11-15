package com.debanshu777.caraml.sdk.discovery.huggingface.api

import com.debanshu777.caraml.sdk.discovery.huggingface.api.error.DataError
import com.debanshu777.caraml.sdk.discovery.huggingface.api.error.Result
import com.debanshu777.caraml.sdk.discovery.huggingface.model.ModelCardDocument
import com.debanshu777.caraml.sdk.discovery.huggingface.model.parseModelCardDocument
import com.debanshu777.caraml.sdk.discovery.huggingface.model.ModelDetailResponse
import com.debanshu777.caraml.sdk.discovery.huggingface.model.modelCardExcerpt
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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelCardTest {
    private val prose = "A compact multilingual language model for local conversations, reasoning, and instruction following."

    @Test
    fun extractsPublisherProseWithoutYamlBadgesOrCode() {
        val card = """
            ---
            license: apache-2.0
            ---
            # Model
            <a href="https://example.com"><img src="badge.svg" /></a>
            [![Badge](badge.svg)](https://example.com)

            ```python
            print("This long code block is not a useful description of the model that should appear in the UI.")
            ```
            ## About
            **A compact multilingual language model** for local conversations, reasoning, and [instruction following](https://example.com).

            ## Usage
            Ignore this unrelated paragraph.
        """.trimIndent()
        assertEquals(prose, parseModelCardDocument("org/model", "a".repeat(40), card).excerpt)
    }

    @Test
    fun missingProseIsAbsentAndLongParagraphIsBounded() {
        assertNull(parseModelCardDocument("org/model", "a".repeat(40), "---\nlicense: mit\n---\n# Model\n![Logo](logo.png)").excerpt)
        assertNull(parseModelCardDocument("org/model", "a".repeat(40), "---\nunterminated metadata").excerpt)
        val excerpt = modelCardExcerpt(prose.repeat(20))!!
        assertTrue(excerpt.length <= 600)
        assertTrue(excerpt.endsWith("…"))
    }

    @Test
    fun fullArticleKeepsTablesImagesCodeAndTextBeyondTheExcerpt() {
        val body = "# Model\n\n${prose.repeat(12)}\n\n| Metric | Value |\n| --- | --- |\n| Score | 42 |\n\n![Architecture](assets/architecture.png)\n\n```python\nprint('full document')\n```"
        assertEquals(body, parseModelCardDocument("org/model", "a".repeat(40), "---\nlicense: mit\n---\n$body").markdown)
        assertTrue(modelCardExcerpt(body)!!.length <= 600)
        assertTrue(parseModelCardDocument("org/model", "a".repeat(40), body).markdown.length > 600)
        assertEquals(body, parseModelCardDocument("org/model", "a".repeat(40), "\uFEFF---\r\nlicense: mit\r\n...\r\n$body").markdown)
    }

    @Test
    fun cardBodyAndExcerptShareFrontMatterNormalization() = runTest {
        val body = "# Model\n\n$prose"
        var response = ""
        val client = HttpClient(MockEngine {
            respond(response, headers = headersOf(HttpHeaders.ContentType, "text/plain"))
        })
        try {
            RemoteHuggingFaceApiService(client, Json, "https://huggingface.co").use { service ->
                for (delimiter in listOf("---", "...", null)) {
                    for (newline in listOf("\n", "\r\n")) {
                        for (prefix in listOf("", "\uFEFF")) {
                            val source = if (delimiter == null) body else "---\nlicense: mit\n$delimiter\n\n$body"
                            response = prefix + source.replace("\n", newline)
                            val document = assertIs<Result.Success<ModelCardDocument, *>>(
                                service.getModelCard("org/model", "a".repeat(40)),
                            ).data
                            assertEquals(body, document.markdown)
                            assertEquals(prose, document.excerpt)
                        }
                    }
                }
            }
        } finally { client.close() }
    }

    @Test
    fun unterminatedFrontMatterPreservesDocumentWithoutExposingMetadataAsExcerpt() = runTest {
        val source = "---\ndescription: $prose\n\n# Model\n\n$prose"
        val client = HttpClient(MockEngine {
            respond("\uFEFF" + source.replace("\n", "\r\n"), headers = headersOf(HttpHeaders.ContentType, "text/plain"))
        })
        try {
            RemoteHuggingFaceApiService(client, Json, "https://huggingface.co").use { service ->
                val document = assertIs<Result.Success<ModelCardDocument, *>>(
                    service.getModelCard("org/model", "a".repeat(40)),
                ).data
                assertEquals(source, document.markdown)
                assertNull(document.excerpt)
            }
        } finally { client.close() }
    }

    @Test
    fun acceptsPublishedGatingAndLanguageShapes() {
        for (gated in listOf("true", "\"manual\"", "\"auto\"")) {
            val model = Json.decodeFromString<ModelDetailResponse>(
                """{"gated":$gated,"cardData":{"language":"en","base_model":"org/base"}}""",
            )
            assertEquals(true, model.gated)
            assertEquals(listOf("en"), model.cardData?.language)
            assertEquals(listOf("org/base"), model.cardData?.baseModel)
        }
        assertEquals(false, Json.decodeFromString<ModelDetailResponse>("""{"gated":false}""").gated)
        assertNull(Json.decodeFromString<ModelDetailResponse>("""{"gated":null}""").gated)
    }

    @Test
    fun cardUsesValidatedImmutableRevisionAndBoundedResponse() = runTest {
        val paths = mutableListOf<String>()
        var body = "# Model\n\n$prose"
        val client = HttpClient(MockEngine { request ->
            paths += request.url.encodedPath
            respond(body, headers = headersOf(HttpHeaders.ContentType, "text/plain"))
        })
        try {
            val service = RemoteHuggingFaceApiService(client, Json, "https://huggingface.co")
            val revision = "a".repeat(40)
            val document = assertIs<Result.Success<ModelCardDocument, *>>(service.getModelCard("org/model", revision)).data
            assertEquals(prose, document.excerpt)
            assertEquals(body, document.markdown)
            assertEquals("org/model", document.repositoryId)
            assertEquals(revision, document.revision)
            assertEquals(listOf("/org/model/raw/$revision/README.md"), paths)
            service.getModelCard("org/model", "main")
            service.getModelCard("org/model?x=y", revision)
            assertEquals(1, paths.size)
            body = "x".repeat(256 * 1024 + 1)
            assertEquals(DataError.Network.PayloadTooLarge, assertIs<Result.Error<*, *>>(service.getModelCard("org/model", revision)).error)
        } finally { client.close() }
    }

    @Test
    fun missingCardAndRedirectStayOptionalErrors() = runTest {
        for (status in listOf(HttpStatusCode.NotFound, HttpStatusCode.Unauthorized, HttpStatusCode.TemporaryRedirect)) {
            var requests = 0
            val client = HttpClient(MockEngine {
                requests++
                respond("", status, headersOf(HttpHeaders.Location, "https://example.com/card"))
            })
            try {
                val service = RemoteHuggingFaceApiService(client, Json, "https://huggingface.co")
                assertIs<Result.Error<*, *>>(service.getModelCard("org/model", "a".repeat(40)))
                assertEquals(1, requests)
            } finally { client.close() }
        }
    }
}
