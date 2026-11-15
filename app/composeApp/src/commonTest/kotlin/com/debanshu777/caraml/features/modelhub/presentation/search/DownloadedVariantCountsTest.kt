package com.debanshu777.caraml.features.modelhub.presentation.search

import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.core.download.transfer.StoragePathProvider
import com.debanshu777.caraml.core.download.transfer.StoredArtifactKind
import com.debanshu777.caraml.core.download.transfer.StoredArtifactSnapshot
import com.debanshu777.caraml.core.download.transfer.ArtifactManifest
import com.debanshu777.caraml.core.download.transfer.ArtifactManifestEntry
import com.debanshu777.caraml.core.download.transfer.artifactBundleId
import com.debanshu777.caraml.core.download.transfer.immutableArtifactStorageLocation
import com.debanshu777.caraml.core.download.transfer.DownloadArtifactIdentity
import com.debanshu777.caraml.core.storage.component.ComponentRepository
import com.debanshu777.caraml.core.storage.component.DownloadedComponentDao
import com.debanshu777.caraml.core.storage.component.DownloadedComponentEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadedVariantCountsTest {
    @Test
    fun usageChangesDoNotRescanButInstallationChangesDo() = runTest {
        val first = model(filename = "first.gguf", path = "/models/first.gguf")
        val second = model(filename = "second.gguf", path = "/models/second.gguf")
        var manifestReads = 0
        val counts = flowOf(
            listOf(first, second),
            listOf(second.copy(usageCount = 1), first),
            listOf(first.copy(componentStatus = LocalModelEntity.STATUS_PARTIAL), second),
            emptyList(),
        ).downloadedVariantCounts(
            ComponentRepository(TestComponents(emptyList())),
            TestStorage(mapOf(first.localPath to 10L, second.localPath to 10L)),
        ) { _, _ ->
            manifestReads++
            null
        }.toList()

        assertEquals(listOf(mapOf("owner/repo" to 2), mapOf("owner/repo" to 1), emptyMap()), counts)
        assertEquals(2, manifestReads, "Read each repository once per changed installation snapshot")
    }

    @Test
    fun durableProjectionRequiresExactCatalogAndFilesystemEvidence() = runTest {
        val main = artifact("model.onnx", 10)
        val tokenizer = artifact("tokenizer.json", 5)
        val bundleId = requireNotNull(artifactBundleId(listOf(main, tokenizer)))
        val primaryEntry = manifestEntry("model", main, bundleId)
        val tokenizerEntry = manifestEntry("tokenizer", tokenizer, bundleId)
        val manifest = requireNotNull(ArtifactManifest.create(listOf(primaryEntry, tokenizerEntry)))
        val mainPath = "/models/${primaryEntry.localRelativePath}"
        val tokenizerPath = "/models/${tokenizerEntry.localRelativePath}"
        val models = listOf(
            model(filename = "model.onnx", path = mainPath, size = 15),
            model(filename = "other.onnx", path = "/models/other.onnx", size = 15),
            model(filename = "partial.gguf", path = "/models/partial.gguf", status = LocalModelEntity.STATUS_PARTIAL),
            model(repository = "legacy/repo", filename = "legacy-Q4.gguf", path = "/models/legacy-Q4.gguf", size = 20),
            model(repository = "legacy/repo", filename = "orphan.onnx", path = "/models/orphan.onnx", size = 20),
            model(repository = "legacy/repo", filename = "shard-00001-of-00002.gguf", path = "/models/shard.gguf", size = 20),
        )
        val components = listOf(component("tokenizer.json", tokenizerPath, 5, bundleId))
        val storage = TestStorage(mapOf(
            mainPath to 10L,
            tokenizerPath to 5L,
            "/models/legacy-Q4.gguf" to 20L,
            "/models/orphan.onnx" to 20L,
            "/models/shard.gguf" to 20L,
        ))

        assertEquals(
            mapOf("owner/repo" to 1, "legacy/repo" to 1),
            aggregateDownloadedVariantCounts(
                models,
                ComponentRepository(TestComponents(components)),
                storage,
            ) { modelId, _ -> if (modelId == "owner/repo") manifest else null },
        )
    }

    @Test
    fun completeShardedGgufDirectoryCountsAsOneVariant() = runTest {
        val first = artifact("model-00001-of-00002.gguf", 10)
        val second = artifact("model-00002-of-00002.gguf", 15)
        val bundleId = requireNotNull(artifactBundleId(listOf(first, second)))
        val bundleRoot = "/models/.caraml-artifacts/$bundleId"
        val entries = listOf(manifestEntry("model", first, bundleId), manifestEntry("shard_2", second, bundleId))
        val manifest = requireNotNull(ArtifactManifest.create(entries))
        val models = listOf(model(
            filename = com.debanshu777.caraml.core.download.diffusion.DIFFUSERS_BUNDLE_DB_FILENAME,
            path = bundleRoot,
            size = 25,
        ))
        assertEquals(
            mapOf("owner/repo" to 1),
            aggregateDownloadedVariantCounts(
                models,
                ComponentRepository(TestComponents(emptyList())),
                TestStorage(emptyMap(), directories = setOf(bundleRoot)),
            ) { _, _ -> manifest },
        )
    }

    private fun model(
        repository: String = "owner/repo",
        filename: String,
        path: String,
        size: Long = 10,
        status: String? = LocalModelEntity.STATUS_READY,
        main: Boolean = true,
    ) = LocalModelEntity(
        modelId = repository,
        filename = filename,
        localPath = path,
        sizeBytes = size,
        downloadedAt = 0,
        author = null,
        libraryName = null,
        pipelineTag = null,
        componentStatus = status,
        isMainModel = main,
    )

    private fun artifact(path: String, bytes: Long) = requireNotNull(
        DownloadArtifactIdentity.create(
            repositoryId = "owner/repo",
            immutableRevision = "c".repeat(40),
            relativePath = path,
            remoteObjectId = "sha256:" + "a".repeat(64),
            expectedBytes = bytes,
        ),
    )

    private fun manifestEntry(role: String, identity: DownloadArtifactIdentity, bundleId: String) =
        requireNotNull(ArtifactManifestEntry.create(
            logicalRole = role,
            identity = identity,
            byteCount = identity.expectedBytes,
            contentSha256 = "b".repeat(64),
            bundleId = bundleId,
            localRelativePath = immutableArtifactStorageLocation(identity, bundleId).localRelativePath,
            layoutRelativePath = identity.relativePath,
        ))

    private fun component(filename: String, path: String, bytes: Long, bundleId: String) = DownloadedComponentEntity(
        repoId = "owner/repo",
        filePath = filename,
        role = "tokenizer",
        localPath = path,
        sizeBytes = bytes,
        downloadedAt = 0,
        immutableRevision = "c".repeat(40),
        remoteObjectId = "sha256:" + "a".repeat(64),
        bundleId = bundleId,
    )

    private class TestComponents(private val components: List<DownloadedComponentEntity>) : DownloadedComponentDao {
        override fun getAllComponents(): Flow<List<DownloadedComponentEntity>> = emptyFlow()
        override suspend fun getComponentsForModel(modelId: String) = components.filter { it.repoId == modelId }
    }

    private class TestStorage(
        private val sizes: Map<String, Long>,
        private val directories: Set<String> = emptySet(),
    ) : StoragePathProvider {
        override fun getModelsStorageDirectory(modelId: String) = "/models"
        override fun getDatabasePath() = "/db/app.db"
        override fun fileExists(path: String) = path in sizes
        override fun getAvailableStorageBytes() = Long.MAX_VALUE
        override fun getTotalStorageBytes() = Long.MAX_VALUE
        override fun inspectDownloadedArtifact(modelId: String, localPath: String): StoredArtifactSnapshot? =
            sizes[localPath]?.let { StoredArtifactSnapshot(StoredArtifactKind.REGULAR_FILE, it, "stamp") }
        override fun isModelFileReadable(path: String) = path in sizes
        override fun isDirectoryReadable(path: String) = path in directories
        override fun getFileSize(path: String) = sizes[path] ?: 0L
        override fun deleteDownloadedModelContent(modelId: String, localPath: String) = true
    }
}
