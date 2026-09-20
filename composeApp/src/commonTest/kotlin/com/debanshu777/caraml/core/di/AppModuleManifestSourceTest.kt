package com.debanshu777.caraml.core.di

import com.debanshu777.caraml.core.recommendation.ArtifactIdentityResolution
import com.debanshu777.caraml.core.recommendation.LocalArtifactIdentityResolver
import com.debanshu777.caraml.core.recommendation.ResolvedArtifactComponent
import com.debanshu777.caraml.core.storage.component.DownloadedComponentEntity
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.huggingfacemanager.download.ArtifactManifest
import com.debanshu777.huggingfacemanager.download.ArtifactManifestEntry
import com.debanshu777.huggingfacemanager.download.DownloadArtifactIdentity
import com.debanshu777.huggingfacemanager.download.StoragePathProvider
import com.debanshu777.huggingfacemanager.download.StoredArtifactKind
import com.debanshu777.huggingfacemanager.download.StoredArtifactSnapshot
import com.debanshu777.huggingfacemanager.download.artifactBundleId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class AppModuleManifestSourceTest {
    @Test
    fun ownerBundleWithExternalImageArtifactsRemainsCompleteAndResolves() = runTest {
        withRoot { storage, root ->
            val fixture = bundleFixture(
                root = root,
                ownerModelId = "owner/image",
                pipelineTag = "text-to-image",
                externalArtifacts = listOf(
                    ExternalArtifact("vae", "components/image", "vae.safetensors", "image-vae"),
                    ExternalArtifact("clip_l", "components/image", "clip_l.safetensors", "image-clip"),
                ),
            )

            assertProductionManifestResolvesExactly(storage, fixture)
        }
    }

    @Test
    fun ownerBundleWithExternalVideoArtifactsRemainsCompleteAndResolves() = runTest {
        withRoot { storage, root ->
            val fixture = bundleFixture(
                root = root,
                ownerModelId = "owner/video",
                pipelineTag = "text-to-video",
                externalArtifacts = listOf(
                    ExternalArtifact("vae", "components/video", "vae.safetensors", "video-vae"),
                    ExternalArtifact("umt5xxl", "encoders/video", "umt5.gguf", "video-text-encoder"),
                ),
            )

            assertProductionManifestResolvesExactly(storage, fixture)
        }
    }

    @Test
    fun duplicateValidatedBundleEntriesRemainRejectedAtManifestBoundary() = runTest {
        val bytes = "primary".encodeToByteArray()
        val identity = identity("owner/model", "a".repeat(40), "model.safetensors", bytes)
        val bundleId = requireNotNull(artifactBundleId(listOf(identity)))
        val first = entry("model", identity, bytes, bundleId)
        val duplicate = entry("model", identity, bytes, bundleId)

        val source = installedModelManifestSource {
            ArtifactManifest.create(listOf(first, duplicate))
        }

        assertNull(source("owner/model"))
    }

    @Test
    fun missingValidatedBundleRemainsMissing() = runTest {
        val source = installedModelManifestSource { null }

        assertNull(source("owner/model"))
    }

    @Test
    fun validatedBundleCancellationPropagates() = runTest {
        val expected = CancellationException("cancelled")
        val source = installedModelManifestSource { throw expected }

        val actual = assertFailsWith<CancellationException> { source("owner/model") }

        assertSame(expected, actual)
    }

    @Test
    fun validatedBundleFailurePropagates() = runTest {
        val expected = IllegalStateException("manifest unavailable")
        val source = installedModelManifestSource { throw expected }

        val actual = assertFailsWith<IllegalStateException> { source("owner/model") }

        assertSame(expected, actual)
    }

    private suspend fun TestScope.assertProductionManifestResolvesExactly(
        storage: TestStorage,
        fixture: BundleFixture,
    ) {
        val source = installedModelManifestSource { ownerModelId ->
            fixture.manifest.takeIf { ownerModelId == fixture.model.modelId }
        }

        assertSame(fixture.manifest, source(fixture.model.modelId))
        assertEquals(
            fixture.manifest.entries.size,
            fixture.manifest.entries.map { it.bundleId to it.logicalRole }.toSet().size,
        )
        assertEquals(
            fixture.manifest.entries.size,
            fixture.manifest.entries.map { it.identity.repositoryId to it.localRelativePath }.toSet().size,
        )

        val resolver = LocalArtifactIdentityResolver(
            storagePathProvider = storage,
            manifestSource = source::invoke,
            hashingDispatcher = StandardTestDispatcher(testScheduler),
        )
        val verified = assertIs<ArtifactIdentityResolution.Verified>(
            resolver.resolve(fixture.model, fixture.components),
        )

        assertEquals(
            fixture.expectedCoverage,
            verified.artifact.components.map { it.coverageKey() }.toSet(),
        )
        assertEquals(fixture.manifest.entries.size, verified.artifact.components.size)
    }

    private fun bundleFixture(
        root: Path,
        ownerModelId: String,
        pipelineTag: String,
        externalArtifacts: List<ExternalArtifact>,
    ): BundleFixture {
        val ownerBytes = "owner-primary".encodeToByteArray()
        val ownerRelativePath = "model.safetensors"
        val ownerPath = write(root / ownerModelId / ownerRelativePath, ownerBytes)
        val artifacts = externalArtifacts.mapIndexed { index, artifact ->
            val bytes = artifact.content.encodeToByteArray()
            val path = write(root / artifact.repositoryId / artifact.relativePath, bytes)
            FixtureArtifact(
                role = artifact.role,
                identity = identity(
                    artifact.repositoryId,
                    ('b'.code + index).toChar().toString().repeat(40),
                    artifact.relativePath,
                    bytes,
                ),
                bytes = bytes,
                localPath = path,
            )
        }
        val ownerIdentity = identity(ownerModelId, "a".repeat(40), ownerRelativePath, ownerBytes)
        val allIdentities = listOf(ownerIdentity) + artifacts.map(FixtureArtifact::identity)
        val bundleId = requireNotNull(artifactBundleId(allIdentities))
        val manifestEntries = listOf(entry("model", ownerIdentity, ownerBytes, bundleId)) + artifacts.map {
            entry(it.role, it.identity, it.bytes, bundleId)
        }
        val manifest = requireNotNull(ArtifactManifest.create(manifestEntries))
        val components = artifacts.mapIndexed { index, artifact ->
            DownloadedComponentEntity(
                id = index.toLong() + 1L,
                repoId = artifact.identity.repositoryId,
                filePath = artifact.identity.relativePath,
                role = artifact.role,
                localPath = artifact.localPath,
                sizeBytes = artifact.bytes.size.toLong(),
                downloadedAt = 1L,
            )
        }
        val expectedCoverage = manifestEntries.map {
            CoverageKey(it.logicalRole, it.identity.repositoryId, it.identity.relativePath)
        }.toSet()
        return BundleFixture(
            manifest = manifest,
            model = LocalModelEntity(
                modelId = ownerModelId,
                filename = ownerRelativePath,
                localPath = ownerPath,
                sizeBytes = ownerBytes.size.toLong(),
                downloadedAt = 1L,
                author = null,
                libraryName = null,
                pipelineTag = pipelineTag,
            ),
            components = components,
            expectedCoverage = expectedCoverage,
        )
    }

    private suspend fun withRoot(block: suspend (TestStorage, Path) -> Unit) {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "caraml-app-module-${Random.nextLong()}"
        FileSystem.SYSTEM.createDirectories(root)
        try {
            block(TestStorage(root), root)
        } finally {
            FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
        }
    }

    private fun write(path: Path, bytes: ByteArray): String {
        FileSystem.SYSTEM.createDirectories(path.parent!!)
        FileSystem.SYSTEM.write(path) { write(bytes) }
        return path.toString()
    }

    private fun identity(
        repositoryId: String,
        revision: String,
        relativePath: String,
        bytes: ByteArray,
    ): DownloadArtifactIdentity = requireNotNull(
        DownloadArtifactIdentity.create(
            repositoryId = repositoryId,
            immutableRevision = revision,
            relativePath = relativePath,
            remoteObjectId = "sha256:${bytes.sha256()}",
            expectedBytes = bytes.size.toLong(),
        ),
    )

    private fun entry(
        role: String,
        identity: DownloadArtifactIdentity,
        bytes: ByteArray,
        bundleId: String,
    ): ArtifactManifestEntry = requireNotNull(
        ArtifactManifestEntry.create(
            logicalRole = role,
            identity = identity,
            byteCount = bytes.size.toLong(),
            contentSha256 = bytes.sha256(),
            bundleId = bundleId,
        ),
    )

    private fun ByteArray.sha256(): String = Buffer().write(this).snapshot().sha256().hex()

    private fun ResolvedArtifactComponent.coverageKey() =
        CoverageKey(logicalRole, repositoryId, repositoryRelativePath)

    private data class ExternalArtifact(
        val role: String,
        val repositoryId: String,
        val relativePath: String,
        val content: String,
    )

    private data class FixtureArtifact(
        val role: String,
        val identity: DownloadArtifactIdentity,
        val bytes: ByteArray,
        val localPath: String,
    )

    private data class BundleFixture(
        val manifest: ArtifactManifest,
        val model: LocalModelEntity,
        val components: List<DownloadedComponentEntity>,
        val expectedCoverage: Set<CoverageKey>,
    )

    private data class CoverageKey(
        val role: String,
        val repositoryId: String,
        val relativePath: String,
    )

    private class TestStorage(private val root: Path) : StoragePathProvider {
        override fun getModelsStorageDirectory(modelId: String): String = (root / modelId).toString()
        override fun getDatabasePath(): String = (root / "app.db").toString()
        override fun fileExists(path: String): Boolean = FileSystem.SYSTEM.exists(path.toPath())
        override fun getAvailableStorageBytes(): Long = Long.MAX_VALUE
        override fun getTotalStorageBytes(): Long = Long.MAX_VALUE
        override fun isModelFileReadable(path: String): Boolean = inspect(path)?.kind == StoredArtifactKind.REGULAR_FILE
        override fun isDirectoryReadable(path: String): Boolean = inspect(path)?.kind == StoredArtifactKind.DIRECTORY
        override fun getFileSize(path: String): Long = inspect(path)?.byteCount ?: 0L
        override fun renameFile(from: String, to: String): Boolean = false
        override fun deleteDownloadedModelContent(modelId: String, localPath: String): Boolean = false

        override fun inspectDownloadedArtifact(modelId: String, localPath: String): StoredArtifactSnapshot? {
            val expectedRoot = (root / modelId).normalized()
            val target = localPath.toPath().normalized()
            if (target != expectedRoot && !target.toString().startsWith("$expectedRoot/")) return null
            return inspect(localPath)
        }

        private fun inspect(path: String): StoredArtifactSnapshot? {
            val metadata = FileSystem.SYSTEM.metadataOrNull(path.toPath()) ?: return null
            if (metadata.symlinkTarget != null) return null
            val kind = when {
                metadata.isRegularFile -> StoredArtifactKind.REGULAR_FILE
                metadata.isDirectory -> StoredArtifactKind.DIRECTORY
                else -> return null
            }
            return StoredArtifactSnapshot(
                kind = kind,
                byteCount = metadata.size ?: 0L,
                changeStamp = "${metadata.lastModifiedAtMillis ?: 0L}:${metadata.size ?: 0L}",
            )
        }
    }
}
