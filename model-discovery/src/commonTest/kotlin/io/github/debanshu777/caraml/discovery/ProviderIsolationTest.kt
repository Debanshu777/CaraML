@file:OptIn(io.github.debanshu777.caraml.core.api.ExperimentalCaraMLApi::class)

package io.github.debanshu777.caraml.discovery

import io.github.debanshu777.caraml.core.api.*
import io.github.debanshu777.caraml.storage.InMemorySdkStorage
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProviderIsolationTest {
    @Test
    fun independentProvidersCanBrowseResolveAndDownloadIntoSharedStorage() = runTest {
        val storage = InMemorySdkStorage()
        val firstProvider = MemoryProvider(ProviderId("company-registry"), byteArrayOf(1, 2))
        val secondProvider = MemoryProvider(ProviderId("local-catalog"), byteArrayOf(3, 4))
        val first = ModelDiscovery.Builder().provider(firstProvider).enableDownloads(storage, storage).build()
        val second = ModelDiscovery.Builder().provider(secondProvider).enableDownloads(storage, storage).build()
        try {
            val firstCandidate = first.search("model").toList().single().items.single()
            val secondCandidate = second.search("model").toList().single().items.single()
            assertFailsWith<IllegalArgumentException> { first.resolve(secondCandidate) }
            val firstArtifact = first.resolve(firstCandidate).single()
            val secondArtifact = second.resolve(secondCandidate).single()
            // Providers may legitimately use identical model, artifact and revision strings.
            assertEquals(firstArtifact.ref.id, secondArtifact.ref.id)
            storage.create(DownloadTask(TaskId("first"), firstArtifact))
            storage.create(DownloadTask(TaskId("second"), secondArtifact))
            first.download(TaskId("first"), "worker").toList()
            first.close()
            second.download(TaskId("second"), "worker").toList()
            assertContentEquals(byteArrayOf(1, 2), storage.readBytes(firstArtifact.ref))
            assertContentEquals(byteArrayOf(3, 4), storage.readBytes(secondArtifact.ref))
            assertEquals(DownloadTaskState.SUCCEEDED, storage.read(TaskId("second"))?.state)
        } finally {
            first.close()
            second.close()
        }
    }

    /** Implements only neutral contracts; no Hub DTOs, HTTP client or native runtime. */
    private class MemoryProvider(override val id: ProviderId, private val bytes: ByteArray) : ModelProvider, ArtifactSource {
        override val providerId = id
        override fun search(query: String, pageToken: String?) =
            flowOf(ModelPage(listOf(ModelCandidate(id, "model", "Model"))))

        override suspend fun resolve(model: ModelCandidate) = listOf(
            ArtifactDescriptor(ArtifactRef(ArtifactId("weights"), id, revision = "release-1"), "weights.bin", bytes.size.toLong()),
        )

        override suspend fun withTransfer(artifact: ArtifactDescriptor, offsetBytes: Long, consume: suspend (ArtifactTransfer) -> Unit) {
            require(artifact.ref.providerId == id)
            consume(object : ArtifactTransfer {
                override val totalBytes = bytes.size.toLong()
                override val validator = "release-1"
                override val body = flowOf(bytes.copyOfRange(offsetBytes.toInt(), bytes.size))
            })
        }
    }
}
