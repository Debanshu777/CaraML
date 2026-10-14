package io.github.debanshu777.caraml.discovery

import io.github.debanshu777.caraml.storage.InMemorySdkStorage
import io.github.debanshu777.caraml.storage.FileArtifactStore

import io.github.debanshu777.caraml.core.api.ArtifactDescriptor
import io.github.debanshu777.caraml.core.api.ArtifactId
import io.github.debanshu777.caraml.core.api.ArtifactRef
import io.github.debanshu777.caraml.core.api.ArtifactSource
import io.github.debanshu777.caraml.core.api.ArtifactTransfer
import io.github.debanshu777.caraml.core.api.DownloadTask
import io.github.debanshu777.caraml.core.api.DownloadTaskState
import io.github.debanshu777.caraml.core.api.ExperimentalCaraMLApi
import io.github.debanshu777.caraml.core.api.ModelCandidate
import io.github.debanshu777.caraml.core.api.ModelPage
import io.github.debanshu777.caraml.core.api.ModelProvider
import io.github.debanshu777.caraml.core.api.ProviderId
import io.github.debanshu777.caraml.core.api.TaskId
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import okio.ByteString.Companion.toByteString
import okio.Path.Companion.toPath
import okio.buffer
import okio.fakefilesystem.FakeFileSystem
import okio.use
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCaraMLApi::class)
class ModelDiscoveryTest {
    @Test
    fun injectedProviderIsIdleUntilCollected() = runTest {
        var searches = 0
        val provider = object : ModelProvider {
            override val id = ProviderId("custom")
            override fun search(query: String, pageToken: String?): Flow<ModelPage> {
                searches++
                return flowOf(ModelPage(listOf(ModelCandidate(id, "model-1", "Model 1"))))
            }
            override suspend fun resolve(model: ModelCandidate) = emptyList<ArtifactDescriptor>()
        }
        val discovery = ModelDiscovery.Builder().provider(provider).build()
        assertEquals(0, searches)
        discovery.search("demo").toList()
        assertEquals(1, searches)

        discovery.close()
        assertFailsWith<IllegalStateException> { discovery.search("after-close").toList() }
    }

    @Test
    fun injectedHubProviderRemainsBorrowedAcrossFacadeClose() = runTest {
        val client = HttpClient(MockEngine { request ->
            if (request.url.encodedPath == "/api/models") respond("[]") else error("Unexpected request")
        }) { followRedirects = false }
        val provider = HuggingFaceProvider(client, baseUrl = "https://hub.example.test")
        val first = ModelDiscovery.Builder().provider(provider).build()
        val second = ModelDiscovery.Builder().provider(provider).build()
        try {
            first.close()
            assertEquals(emptyList(), second.search("still-open").toList().single().items)
        } finally {
            first.close()
            second.close()
            provider.close()
            client.close()
        }
    }

    @Test
    fun hubBrowseAndTransferUseBorrowedClientAndStripHubTokenFromSignedRedirect() = runTest {
        val commit = "a".repeat(40)
        val seen = mutableListOf<Triple<String, String?, String?>>()
        val client = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            val auth = request.headers[HttpHeaders.Authorization]
            seen += Triple(path, auth, request.headers[HttpHeaders.Range])
            when {
                path == "/api/models" -> respond("[{\"id\":\"org/model\",\"sha\":\"$commit\"}]")
                path.startsWith("/api/models/org/model/tree/") -> respond(
                    "[{\"type\":\"file\",\"path\":\"model.gguf\",\"size\":4}]",
                )
                path.startsWith("/org/model/resolve/") -> respond(
                    content = "",
                    status = HttpStatusCode.Found,
                    headers = headersOf(HttpHeaders.Location, "https://cdn.example.test/file?sig=abc"),
                )
                path == "/file" -> respond(
                    content = "data",
                    headers = headersOf(HttpHeaders.ContentLength, "4"),
                )
                path == "/health" -> respond("ok")
                else -> error("Unexpected request path: $path")
            }
        }) { followRedirects = false }
        val storage = InMemorySdkStorage()
        val discovery = ModelDiscovery.Builder()
            .huggingFace(client, token = "secret", baseUrl = "https://hub.example.test")
            .enableDownloads(storage, storage)
            .build()
        try {
            val candidate = discovery.search("model").toList().single().items.single()
            val artifact = discovery.resolve(candidate).single()
            storage.create(DownloadTask(TaskId("hub-task"), artifact))
            discovery.download(TaskId("hub-task"), "worker").toList()

            assertEquals(DownloadTaskState.SUCCEEDED, storage.read(TaskId("hub-task"))?.state)
            assertEquals("secret", seen.first { it.first == "/api/models" }.second?.removePrefix("Bearer "))
            assertEquals("secret", seen.first { it.first.startsWith("/api/models/org/model/tree/") }.second?.removePrefix("Bearer "))
            assertEquals("secret", seen.first { it.first.startsWith("/org/model/resolve/") }.second?.removePrefix("Bearer "))
            assertEquals(null, seen.first { it.first == "/file" }.second)
            discovery.close()
            client.get("https://hub.example.test/health")
            assertEquals("/health", seen.last().first)
        } finally {
            discovery.close()
            client.close()
        }
    }

    @Test
    fun resumedHubTransferRejectsWrongContentRangeAndCarriesRangeWithoutCredential() = runTest {
        val commit = "b".repeat(40)
        val seen = mutableListOf<Triple<String, String?, String?>>()
        val client = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            seen += Triple(path, request.headers[HttpHeaders.Authorization], request.headers[HttpHeaders.Range])
            when {
                path.startsWith("/org/model/resolve/") -> respond(
                    content = "",
                    status = HttpStatusCode.Found,
                    headers = headersOf(HttpHeaders.Location, "https://cdn.example.test/file"),
                )
                path == "/file" -> respond(
                    content = "cd",
                    status = HttpStatusCode.PartialContent,
                    headers = headersOf(
                        HttpHeaders.ContentRange to listOf("bytes 1-2/4"),
                        HttpHeaders.ContentLength to listOf("2"),
                    ),
                )
                else -> error("Unexpected request path: $path")
            }
        }) { followRedirects = false }
        try {
            val provider = HuggingFaceProvider(client, token = "secret", baseUrl = "https://hub.example.test")
            val descriptor = ArtifactDescriptor(
                ArtifactRef(ArtifactId("org/model\u0000model.gguf"), ProviderId("huggingface"), commit),
                "model.gguf",
                4,
            )
            assertFailsWith<HubProtocolException> { provider.withTransfer(descriptor, offsetBytes = 2) { error("Invalid range accepted") } }
            assertEquals("bytes=2-", seen[0].third)
            assertEquals("bytes=2-", seen[1].third)
            assertEquals("Bearer secret", seen[0].second)
            assertEquals(null, seen[1].second)
        } finally {
            client.close()
        }
    }

    @Test
    fun boundedRetryResumesFromPersistedStageAndChecksExpectedDigest() = runTest {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val digest = bytes.toByteString().sha256().hex()
        val provider = TestProvider()
        val source = RetryThenResumeSource(provider.id, bytes)
        val storage = InMemorySdkStorage()
        val descriptor = ArtifactDescriptor(
            ArtifactRef(ArtifactId("custom:model"), provider.id, "release-v1", digest),
            "model.bin",
            bytes.size.toLong(),
        )
        val taskId = TaskId("retry-task")
        storage.create(DownloadTask(taskId, descriptor))
        val discovery = ModelDiscovery.Builder()
            .provider(provider)
            .enableDownloads(storage, storage, source)
            .build()
        try {
            discovery.download(taskId, "worker").toList()
            assertEquals(listOf(0L, 2L), source.offsets)
            assertEquals(DownloadTaskState.SUCCEEDED, storage.read(taskId)?.state)
            assertEquals(bytes.toList(), storage.readBytes(descriptor.ref)?.toList())
        } finally {
            discovery.close()
        }
    }

    @Test
    fun downloadPublishesEngineReadableFileThroughFileArtifactStore() = runTest {
        val bytes = byteArrayOf(11, 12, 13, 14)
        val provider = TestProvider()
        val storage = InMemorySdkStorage()
        val fileSystem = FakeFileSystem()
        val fileStore = FileArtifactStore("/models".toPath(), fileSystem)
        val descriptor = ArtifactDescriptor(
            ArtifactRef(
                ArtifactId("custom:file-model"),
                provider.id,
                "release-v1",
                bytes.toByteString().sha256().hex(),
            ),
            "model.gguf",
            bytes.size.toLong(),
        )
        val taskId = TaskId("file-download")
        storage.create(DownloadTask(taskId, descriptor))
        val discovery = ModelDiscovery.Builder()
            .provider(provider)
            .enableDownloads(storage, fileStore, object : ArtifactSource {
                override val providerId = provider.id
                override suspend fun withTransfer(artifact: ArtifactDescriptor, offsetBytes: Long, consume: suspend (ArtifactTransfer) -> Unit) {
                    consume(TestTransfer(flowOf(bytes.copyOfRange(offsetBytes.toInt(), bytes.size)), bytes.size.toLong()))
                }
            })
            .build()
        try {
            discovery.download(taskId, "file-worker").toList()
            assertEquals(DownloadTaskState.SUCCEEDED, storage.read(taskId)?.state)
            val lease = assertNotNull(fileStore.acquire(descriptor.ref))
            val localPath = (lease.location as io.github.debanshu777.caraml.core.api.ArtifactLocation.LocalFile).path
            assertEquals(bytes.toList(), fileSystem.source(localPath.toPath()).buffer().use { it.readByteArray() }.toList())
            lease.close()
            assertTrue(fileStore.remove(descriptor.ref))
            fileSystem.checkNoOpenFiles()
        } finally {
            discovery.close()
        }
    }

    @Test
    fun closingServiceCancelsDownloadAndLeavesResumableTask() = runTest {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val provider = TestProvider()
        val source = CancelThenResumeSource(provider.id, bytes)
        val storage = InMemorySdkStorage()
        val descriptor = ArtifactDescriptor(ArtifactRef(ArtifactId("custom:resume"), provider.id), "model.bin", 4)
        val taskId = TaskId("cancel-task")
        storage.create(DownloadTask(taskId, descriptor))
        val first = ModelDiscovery.Builder().provider(provider).enableDownloads(storage, storage, source).build()
        val job = launch { first.download(taskId, "first-worker").collect() }
        runCurrent()
        first.close()
        job.join()
        assertEquals(DownloadTaskState.PAUSED, storage.read(taskId)?.state)
        assertEquals(2L, storage.read(taskId)?.bytesReceived)

        val second = ModelDiscovery.Builder().provider(provider).enableDownloads(storage, storage, source).build()
        try {
            second.download(taskId, "second-worker").toList()
            assertEquals(2L, source.offsets.last())
            assertEquals(DownloadTaskState.SUCCEEDED, storage.read(taskId)?.state)
        } finally {
            second.close()
        }
    }

    private class TestProvider : ModelProvider {
        override val id = ProviderId("custom")
        override fun search(query: String, pageToken: String?) = flowOf(ModelPage(emptyList()))
        override suspend fun resolve(model: ModelCandidate) = emptyList<ArtifactDescriptor>()
    }

    private class RetryThenResumeSource(
        override val providerId: ProviderId,
        private val bytes: ByteArray,
    ) : ArtifactSource {
        val offsets = mutableListOf<Long>()
        private var first = true
        override suspend fun withTransfer(artifact: ArtifactDescriptor, offsetBytes: Long, consume: suspend (ArtifactTransfer) -> Unit) {
            offsets += offsetBytes
            val body = if (first) {
                first = false
                flow {
                    emit(bytes.copyOfRange(0, 2))
                    throw IOException("injected interrupted stream")
                }
            } else {
                flowOf(bytes.copyOfRange(offsetBytes.toInt(), bytes.size))
            }
            consume(TestTransfer(body, bytes.size.toLong()))
        }
    }

    private class CancelThenResumeSource(
        override val providerId: ProviderId,
        private val bytes: ByteArray,
    ) : ArtifactSource {
        val offsets = mutableListOf<Long>()
        private var first = true
        override suspend fun withTransfer(artifact: ArtifactDescriptor, offsetBytes: Long, consume: suspend (ArtifactTransfer) -> Unit) {
            offsets += offsetBytes
            val body = if (first) {
                first = false
                flow {
                    emit(bytes.copyOfRange(0, 2))
                    awaitCancellation()
                }
            } else flowOf(bytes.copyOfRange(offsetBytes.toInt(), bytes.size))
            consume(TestTransfer(body, bytes.size.toLong()))
        }
    }

    private class TestTransfer(
        override val body: Flow<ByteArray>,
        override val totalBytes: Long?,
    ) : ArtifactTransfer {
        override val validator: String? = null
    }
}
