package com.debanshu777.huggingfacemanager.api

import com.debanshu777.huggingfacemanager.api.error.DataError
import com.debanshu777.huggingfacemanager.api.error.Result
import com.debanshu777.huggingfacemanager.model.ModelDetailResponse
import com.debanshu777.huggingfacemanager.repository.HuggingFaceRepository
import com.debanshu777.huggingfacemanager.usecase.GetModelFileTreeUseCase
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class BoundedResponseTest {
    @Test
    fun redirectIsRejectedBeforeAnUntrustedLocationIsRequested() = runTest {
        var requests = 0
        val client = HttpClient(MockEngine {
            requests++
            if (requests == 1) {
                respond(
                    content = "",
                    status = HttpStatusCode.Found,
                    headers = headersOf(HttpHeaders.Location, "https://attacker.invalid/model"),
                )
            } else {
                respond("{\"id\":\"attacker/model\"}")
            }
        })
        try {
            val result = ClientWrapper(client, Json).networkGetUsecase<ModelDetailResponse>(
                endpoint = "https://huggingface.co/api/models/owner/model",
            )

            assertEquals(Result.Error(DataError.Network.Unknown), result)
            assertEquals(1, requests)
        } finally {
            client.close()
        }
    }

    @Test
    fun chunkedBodyOverLimitIsRejectedBeforeDecode() = runTest {
        val client = HttpClient(MockEngine {
            respond("{\"payload\":\"${"x".repeat(64)}\"}")
        })
        try {
            val result = ClientWrapper(client, Json).networkGetUsecase<ModelDetailResponse>(
                endpoint = "https://huggingface.co/oversized",
                maxResponseBytes = 32L,
            )
            assertEquals(Result.Error(DataError.Network.PayloadTooLarge), result)
        } finally {
            client.close()
        }
    }

    @Test
    fun declaredBodyOverLimitIsRejectedWithoutReadingOrDecoding() = runTest {
        val client = HttpClient(MockEngine {
            respond(
                content = "{}",
                headers = headersOf(HttpHeaders.ContentLength, "1024"),
            )
        })
        try {
            val result = ClientWrapper(client, Json).networkGetUsecase<ModelDetailResponse>(
                endpoint = "https://huggingface.co/oversized",
                maxResponseBytes = 32L,
            )
            assertEquals(Result.Error(DataError.Network.PayloadTooLarge), result)
        } finally {
            client.close()
        }
    }

    @Test
    fun validSameOriginCursorIsExtractedAndRequestIsRebuilt() = runTest {
        val seenUrls = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            seenUrls += request.url.toString()
            if (seenUrls.size == 1) {
                respond(
                    content = "[{\"path\":\"one.gguf\",\"size\":1,\"type\":\"file\"}]",
                    status = HttpStatusCode.OK,
                    headers = headersOf(
                        HttpHeaders.Link,
                        "<https://huggingface.co/api/models/owner/model/tree/$REVISION?cursor=opaque%3Dtoken>; rel=\"next\"",
                    ),
                )
            } else {
                respond("[{\"path\":\"two.gguf\",\"size\":2,\"type\":\"file\"}]")
            }
        })
        try {
            val service = RemoteHuggingFaceApiService(client, Json, "https://huggingface.co")
            val result = assertIs<Result.Success<List<com.debanshu777.huggingfacemanager.model.ModelFileTreeResponse>, DataError.Network>>(
                service.getModelFileTree("owner/model", REVISION),
            )
            assertEquals(listOf("one.gguf", "two.gguf"), result.data.map { it.path })
            assertEquals("opaque=token", io.ktor.http.Url(seenUrls[1]).parameters["cursor"])
            assertEquals("huggingface.co", io.ktor.http.Url(seenUrls[1]).host)
        } finally {
            client.close()
        }
    }

    @Test
    fun offOriginOrMalformedNextLinkIsRejectedWithoutFollowingIt() = runTest {
        var requests = 0
        val client = HttpClient(MockEngine {
            requests++
            respond(
                content = "[]",
                headers = headersOf(
                    HttpHeaders.Link,
                    "<https://attacker.invalid/api/models/owner/model/tree/$REVISION?cursor=stolen>; rel=\"next\"",
                ),
            )
        })
        try {
            val service = RemoteHuggingFaceApiService(client, Json, "https://huggingface.co")
            assertEquals(
                Result.Error(DataError.Network.Serialization),
                service.getModelFileTree("owner/model", REVISION),
            )
            assertEquals(1, requests)
        } finally {
            client.close()
        }
    }

    @Test
    fun duplicatePathsAcrossPagesAreRejected() = runTest {
        var page = 0
        val client = HttpClient(MockEngine {
            page++
            respond(
                content = "[{\"path\":\"same.gguf\",\"size\":1,\"type\":\"file\"}]",
                headers = if (page == 1) {
                    headersOf(
                        HttpHeaders.Link,
                        "<https://huggingface.co/api/models/owner/model/tree/$REVISION?cursor=next>; rel=\"next\"",
                    )
                } else {
                    headersOf()
                },
            )
        })
        try {
            val service = RemoteHuggingFaceApiService(client, Json, "https://huggingface.co")
            assertEquals(
                Result.Error(DataError.Network.Serialization),
                service.getModelFileTree("owner/model", REVISION),
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun pageAndEntryCeilingsTerminateRetrieval() = runTest {
        var pageRequests = 0
        val pagedClient = HttpClient(MockEngine {
            pageRequests++
            respond(
                content = "[{\"path\":\"$pageRequests.gguf\",\"size\":1,\"type\":\"file\"}]",
                headers = headersOf(
                    HttpHeaders.Link,
                    "<https://huggingface.co/api/models/owner/model/tree/$REVISION?cursor=page-$pageRequests>; rel=\"next\"",
                ),
            )
        })
        try {
            val service = RemoteHuggingFaceApiService(pagedClient, Json, "https://huggingface.co")
            assertEquals(
                Result.Error(DataError.Network.PayloadTooLarge),
                service.getModelFileTree("owner/model", REVISION),
            )
            assertEquals(64, pageRequests)
        } finally {
            pagedClient.close()
        }

        val entries = (0..4_096).joinToString(prefix = "[", postfix = "]") {
            "{\"path\":\"$it.gguf\",\"size\":1,\"type\":\"file\"}"
        }
        val entryClient = HttpClient(MockEngine { respond(entries) })
        try {
            val service = RemoteHuggingFaceApiService(entryClient, Json, "https://huggingface.co")
            assertEquals(
                Result.Error(DataError.Network.PayloadTooLarge),
                service.getModelFileTree("owner/model", REVISION),
            )
        } finally {
            entryClient.close()
        }
    }

    @Test
    fun entryBeyondRemainingBudgetIsRejectedBeforeItIsDecoded() = runTest {
        val entries = (0 until 4_096).joinToString(prefix = "[", postfix = ",\"not-an-object\"]") {
            "{\"path\":\"$it.gguf\",\"size\":1,\"type\":\"file\"}"
        }
        val client = HttpClient(MockEngine { respond(entries) })
        try {
            val service = RemoteHuggingFaceApiService(client, Json, "https://huggingface.co")
            assertEquals(
                Result.Error(DataError.Network.PayloadTooLarge),
                service.getModelFileTree("owner/model", REVISION),
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun cancellationStopsPaginationImmediately() = runTest {
        val client = HttpClient(MockEngine { throw CancellationException("stop") })
        try {
            val service = RemoteHuggingFaceApiService(client, Json, "https://huggingface.co")
            assertFailsWith<CancellationException> {
                service.getModelFileTree("owner/model", REVISION)
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun configUsesFixedOriginImmutableRevisionStrictJsonAndOneMibLimit() = runTest {
        val seenPaths = mutableListOf<String>()
        var response = "{\"num_hidden_layers\":32}"
        val client = HttpClient(MockEngine { request ->
            seenPaths += request.url.encodedPath
            respond(response)
        })
        try {
            val service = RemoteHuggingFaceApiService(client, Json { ignoreUnknownKeys = true }, "https://huggingface.co")
            assertIs<Result.Success<*, *>>(service.getModelConfig("owner/model", REVISION))
            assertEquals("/owner/model/resolve/$REVISION/config.json", seenPaths.single())

            response = "{\"num_hidden_layers\":32,\"unexpected\":true}"
            assertEquals(
                Result.Error(DataError.Network.Serialization),
                service.getModelConfig("owner/model", REVISION),
            )

            assertEquals(
                Result.Error(DataError.Network.Unknown),
                service.getModelConfig("owner/model", "main"),
            )
            assertEquals(2, seenPaths.size)
        } finally {
            client.close()
        }
    }

    @Test
    fun recommendationDetailRejectsUnknownFields() = runTest {
        val client = HttpClient(MockEngine {
            respond("{\"id\":\"owner/model\",\"sha\":\"$REVISION\",\"unexpected\":true}")
        })
        try {
            val service = RemoteHuggingFaceApiService(
                client,
                Json { ignoreUnknownKeys = true },
                "https://huggingface.co",
            )
            assertEquals(
                Result.Error(DataError.Network.Serialization),
                service.getModelDetail("owner/model"),
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun recommendationDetailRejectsQuotedNumericTypes() = runTest {
        val client = HttpClient(MockEngine {
            respond("{\"id\":\"owner/model\",\"sha\":\"$REVISION\",\"downloads\":\"1\"}")
        })
        try {
            val service = RemoteHuggingFaceApiService(
                client,
                Json { isLenient = true; ignoreUnknownKeys = true },
                "https://huggingface.co",
            )
            assertEquals(
                Result.Error(DataError.Network.Serialization),
                service.getModelDetail("owner/model"),
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun recommendationDetailRejectsOversizedNestedCollections() = runTest {
        val tags = List(257) { "\"tag-$it\"" }.joinToString()
        val client = HttpClient(MockEngine {
            respond("{\"id\":\"owner/model\",\"sha\":\"$REVISION\",\"tags\":[$tags]}")
        })
        try {
            val service = RemoteHuggingFaceApiService(client, Json, "https://huggingface.co")
            assertEquals(
                Result.Error(DataError.Network.Serialization),
                service.getModelDetail("owner/model"),
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun recommendationListUsesTheStrictVersionedProjection() = runTest {
        val client = HttpClient(MockEngine {
            respond("{\"models\":[{\"id\":\"owner/model\",\"unexpected\":true}]}")
        })
        try {
            val service = RemoteHuggingFaceApiService(
                client,
                Json { ignoreUnknownKeys = true },
                "https://huggingface.co",
            )
            assertIs<Result.Success<*, *>>(service.listModels(ListModelsParams()))
            assertEquals(
                Result.Error(DataError.Network.Serialization),
                service.listRecommendationModels(ListModelsParams()),
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun legacyTreeUseCaseFetchesAndUsesImmutableDetailSha() = runTest {
        val paths = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            paths += request.url.encodedPath
            if (paths.size == 1) {
                respond("{\"id\":\"owner/model\",\"sha\":\"$REVISION\"}")
            } else {
                respond("[{\"path\":\"model.gguf\",\"size\":1,\"type\":\"file\"}]")
            }
        })
        try {
            val useCase = GetModelFileTreeUseCase(
                HuggingFaceRepository(
                    RemoteHuggingFaceApiService(client, Json, "https://huggingface.co"),
                ),
            )
            assertIs<Result.Success<*, *>>(useCase("owner/model"))
            assertEquals(
                listOf(
                    "/api/models/owner/model",
                    "/api/models/owner/model/tree/$REVISION",
                ),
                paths,
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun legacyTreeUseCaseRejectsMutableRevisionBeforeTreeRequest() = runTest {
        var requests = 0
        val client = HttpClient(MockEngine {
            requests++
            respond("{\"id\":\"owner/model\",\"sha\":\"main\"}")
        })
        try {
            val useCase = GetModelFileTreeUseCase(
                HuggingFaceRepository(
                    RemoteHuggingFaceApiService(client, Json, "https://huggingface.co"),
                ),
            )
            assertEquals(Result.Error(DataError.Network.Unknown), useCase("owner/model"))
            assertEquals(1, requests)
        } finally {
            client.close()
        }
    }

    private companion object {
        const val REVISION = "0123456789abcdef0123456789abcdef01234567"
    }
}
