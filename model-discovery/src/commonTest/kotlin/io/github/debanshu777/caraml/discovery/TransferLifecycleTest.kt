@file:OptIn(io.github.debanshu777.caraml.core.api.ExperimentalCaraMLApi::class)

package io.github.debanshu777.caraml.discovery

import io.github.debanshu777.caraml.core.api.*
import io.github.debanshu777.caraml.storage.InMemorySdkStorage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class TransferLifecycleTest {
    private fun descriptor() = ArtifactDescriptor(
        ArtifactRef(ArtifactId("org/model\u0000model.gguf"), ProviderId("huggingface"), "a".repeat(40)),
        "model.gguf", 4,
    )

    @Test
    fun transferDeliversPartialBodyBeforeResponseEof() = runTest {
        val body = ByteChannel(autoFlush = true)
        val host = HttpClient(MockEngine { respond(body, headers = headersOf(HttpHeaders.ContentLength, "4")) })
        val provider = HuggingFaceProvider(host)
        try {
            body.writeFully(byteArrayOf(1, 2))
            withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    provider.withTransfer(descriptor()) { transfer ->
                        assertContentEquals(byteArrayOf(1, 2), transfer.body.first())
                        assertFalse(body.isClosedForRead)
                    }
                }
            }
        } finally {
            body.cancel()
            provider.close()
            host.close()
        }
    }

    @Test
    fun oversizedMetadataIsRejectedBeforeReadingBody() = runTest {
        val body = ByteChannel()
        val host = HttpClient(MockEngine {
            respond(body, headers = headersOf(HttpHeaders.ContentLength, "999999999"))
        })
        val provider = HuggingFaceProvider(host)
        try {
            withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    assertFailsWith<HubProtocolException> { provider.search("large").toList() }
                }
            }
        } finally { body.cancel(); provider.close(); host.close() }
    }

    @Test
    fun signedRedirectStripsInheritedAuthorizationWithoutChangingHostClient() = runTest {
        val authorization = mutableListOf<String?>()
        val host = HttpClient(MockEngine { request ->
            authorization += request.headers[HttpHeaders.Authorization]
            if (request.url.host == "huggingface.co") respond("", HttpStatusCode.Found,
                headersOf(HttpHeaders.Location, "https://cdn.example.test/file"))
            else respond("data", headers = headersOf(HttpHeaders.ContentLength, "4"))
        }) { defaultRequest { bearerAuth("host-secret") } }
        val provider = HuggingFaceProvider(host)
        try {
            provider.withTransfer(descriptor()) { assertEquals(4, it.body.toList().sumOf { chunk -> chunk.size }) }
            host.get("https://host.example.test/health")
            assertEquals(listOf("Bearer host-secret", null, "Bearer host-secret"), authorization)
        } finally { provider.close(); host.close() }
    }

    @Test
    fun concurrentCloseWaitsForTransferCleanup() = runTest {
        val started = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val provider = object : ModelProvider, ArtifactSource {
            override val id = ProviderId("huggingface")
            override val providerId = id
            override fun search(query: String, pageToken: String?) = emptyFlow<ModelPage>()
            override suspend fun resolve(model: ModelCandidate) = listOf(descriptor())
            override suspend fun withTransfer(artifact: ArtifactDescriptor, offsetBytes: Long, consume: suspend (ArtifactTransfer) -> Unit) {
                try {
                    consume(object : ArtifactTransfer {
                        override val totalBytes = 4L
                        override val validator: String? = null
                        override val body = flow<ByteArray> { started.complete(Unit); awaitCancellation() }
                    })
                } finally {
                    withContext(NonCancellable) { cleaning.complete(Unit); release.await() }
                }
            }
        }
        val storage = InMemorySdkStorage()
        val task = TaskId("concurrent-close")
        storage.create(DownloadTask(task, descriptor()))
        val discovery = ModelDiscovery.Builder().provider(provider).enableDownloads(storage, storage, provider).build()
        val download = launch { discovery.download(task, "worker").collect() }
        started.await()
        val first = async { discovery.close() }
        cleaning.await()
        val second = async { discovery.close() }
        runCurrent()
        val returnedEarly = second.isCompleted
        release.complete(Unit)
        first.await()
        second.await()
        download.join()
        assertFalse(returnedEarly)
        assertEquals(DownloadTaskState.PAUSED, storage.read(task)?.state)
    }
}
