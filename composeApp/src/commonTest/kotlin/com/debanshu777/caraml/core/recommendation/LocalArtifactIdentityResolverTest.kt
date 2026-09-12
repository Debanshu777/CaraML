package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.storage.component.DownloadedComponentEntity
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.huggingfacemanager.download.ArtifactManifest
import com.debanshu777.huggingfacemanager.download.ArtifactManifestEntry
import com.debanshu777.huggingfacemanager.download.DownloadArtifactIdentity
import com.debanshu777.huggingfacemanager.download.StoragePathProvider
import com.debanshu777.huggingfacemanager.download.StoredArtifactKind
import com.debanshu777.huggingfacemanager.download.StoredArtifactSnapshot
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class LocalArtifactIdentityResolverTest {
    @Test
    fun validSingleFileHubManifestProducesCommitIdentity() = runTest {
        withRoot { storage, root ->
            val bytes = "model".encodeToByteArray()
            val path = write(root / "owner/model/model.gguf", bytes)
            val manifest = manifest(
                manifestEntry("model", "owner/model", "a".repeat(40), "model.gguf", bytes),
            )
            val resolver = resolver(storage, manifest)

            val verified = assertIs<ArtifactIdentityResolution.Verified>(
                resolver.resolve(model(path, bytes.size.toLong()), emptyList()),
            )

            val revision = assertIs<RevisionIdentity.HubCommit>(verified.artifact.revisionIdentity)
            assertEquals(listOf(RepositoryCommit("owner/model", "a".repeat(40))), revision.commits)
            assertEquals(bytes.sha256(), verified.artifact.components.single().contentSha256)
        }
    }

    @Test
    fun validMultiRepositoryDiffusionBundleKeepsEveryRepositoryCommitAndRole() = runTest {
        withRoot { storage, root ->
            val primary = "primary".encodeToByteArray()
            val vae = "vae".encodeToByteArray()
            val primaryPath = write(root / "owner/model/model.safetensors", primary)
            val vaePath = write(root / "other/vae/vae.safetensors", vae)
            val identities = listOf(
                downloadIdentity("owner/model", "a".repeat(40), "model.safetensors", primary.size.toLong()),
                downloadIdentity("other/vae", "b".repeat(40), "vae.safetensors", vae.size.toLong()),
            )
            val bundle = checkNotNull(com.debanshu777.huggingfacemanager.download.artifactBundleId(identities))
            val manifest = checkNotNull(
                ArtifactManifest.create(
                    listOf(
                        checkNotNull(ArtifactManifestEntry.create("model", identities[0], primary.size.toLong(), primary.sha256(), bundle)),
                        checkNotNull(ArtifactManifestEntry.create("vae", identities[1], vae.size.toLong(), vae.sha256(), bundle)),
                    ),
                ),
            )
            val components = listOf(component("other/vae", "vae.safetensors", "vae", vaePath, vae.size.toLong()))

            val verified = assertIs<ArtifactIdentityResolution.Verified>(
                resolver(storage, manifest).resolve(
                    model(primaryPath, primary.size.toLong(), filename = "model.safetensors"),
                    components,
                ),
            )

            assertEquals(listOf("model", "vae"), verified.artifact.components.map { it.logicalRole })
            assertEquals(
                listOf(RepositoryCommit("other/vae", "b".repeat(40)), RepositoryCommit("owner/model", "a".repeat(40))),
                assertIs<RevisionIdentity.HubCommit>(verified.artifact.revisionIdentity).commits,
            )
        }
    }

    @Test
    fun staleSizeOrDigestEvidenceIsRejected() = runTest {
        withRoot { storage, root ->
            val bytes = "real".encodeToByteArray()
            val path = write(root / "owner/model/model.gguf", bytes)
            val staleDigest = manifest(
                manifestEntry("model", "owner/model", "a".repeat(40), "model.gguf", "fake".encodeToByteArray()),
            )
            val staleSize = manifest(
                manifestEntry("model", "owner/model", "a".repeat(40), "model.gguf", bytes + byteArrayOf(0)),
            )

            assertIs<ArtifactIdentityResolution.Rejected>(
                resolver(storage, staleDigest).resolve(model(path, bytes.size.toLong()), emptyList()),
            )
            assertIs<ArtifactIdentityResolution.Rejected>(
                resolver(storage, staleSize).resolve(model(path, bytes.size.toLong()), emptyList()),
            )
        }
    }

    @Test
    fun pathEscapeSymlinkEscapeAndUnreadableFileAreRejected() = runTest {
        withRoot { storage, root ->
            val outside = write(root.parent!! / "outside-${Random.nextInt()}.gguf", byteArrayOf(1))
            val escaped = model(outside, 1)
            assertIs<ArtifactIdentityResolution.Rejected>(resolver(storage, null).resolve(escaped, emptyList()))

            val link = root / "owner/model/link.gguf"
            FileSystem.SYSTEM.createDirectories(link.parent!!)
            FileSystem.SYSTEM.createSymlink(link, outside.toPath())
            assertIs<ArtifactIdentityResolution.Rejected>(
                resolver(storage, null).resolve(model(link.toString(), 1, "link.gguf"), emptyList()),
            )

            storage.unreadable += (root / "owner/model/model.gguf").toString()
            val unreadable = write(root / "owner/model/model.gguf", byteArrayOf(1))
            assertIs<ArtifactIdentityResolution.Rejected>(
                resolver(storage, null).resolve(model(unreadable, 1), emptyList()),
            )
            FileSystem.SYSTEM.delete(outside.toPath(), mustExist = false)
        }
    }

    @Test
    fun duplicateOrMoreThanSixtyFourComponentsAreRejectedBeforeHashing() = runTest {
        withRoot { storage, root ->
            val main = write(root / "owner/model/model.safetensors", byteArrayOf(1))
            val duplicatePath = write(root / "component/repo/dup.safetensors", byteArrayOf(2))
            val duplicate = component("component/repo", "dup.safetensors", "vae", duplicatePath, 1)
            val resolver = resolver(storage, null)

            assertIs<ArtifactIdentityResolution.Rejected>(
                resolver.resolve(model(main, 1, "model.safetensors"), listOf(duplicate, duplicate.copy(id = 2))),
            )
            val tooMany = (0..64).map { index ->
                val path = write(root / "component/repo/$index.safetensors", byteArrayOf(index.toByte()))
                component("component/repo", "$index.safetensors", "role_$index", path, 1, id = index.toLong() + 1)
            }
            assertIs<ArtifactIdentityResolution.Rejected>(
                resolver.resolve(model(main, 1, "model.safetensors"), tooMany),
            )
        }
    }

    @Test
    fun legacySingleFileAndAllowlistedBundleAreContentAddressedAndRootIndependent() = runTest {
        withRoot { storage, firstRoot ->
            val firstMain = write(firstRoot / "owner/model/model.gguf", byteArrayOf(1, 2, 3))
            val single = assertIs<ArtifactIdentityResolution.Verified>(
                resolver(storage, null).resolve(model(firstMain, 3), emptyList()),
            )
            assertIs<RevisionIdentity.LocalContent>(single.artifact.revisionIdentity)

            withRoot { secondStorage, secondRoot ->
                val secondMain = write(secondRoot / "owner/model/model.gguf", byteArrayOf(1, 2, 3))
                val same = assertIs<ArtifactIdentityResolution.Verified>(
                    resolver(secondStorage, null).resolve(model(secondMain, 3), emptyList()),
                )
                assertEquals(single.artifact.identity.revision, same.artifact.identity.revision)

                write(secondRoot / "owner/model/model.gguf", byteArrayOf(1, 2, 4))
                val changed = assertIs<ArtifactIdentityResolution.Verified>(
                    resolver(secondStorage, null).resolve(model(secondMain, 3), emptyList()),
                )
                assertNotEquals(single.artifact.identity.revision, changed.artifact.identity.revision)
            }

            val componentPath = write(firstRoot / "component/repo/vae.safetensors", byteArrayOf(9, 8))
            val bundled = assertIs<ArtifactIdentityResolution.Verified>(
                resolver(storage, null).resolve(
                    model(firstMain, 3),
                    listOf(component("component/repo", "vae.safetensors", "vae", componentPath, 2)),
                ),
            )
            assertEquals(2, bundled.artifact.components.size)
            assertTrue(FileSystem.SYSTEM.exists(firstRoot / "owner/model/${LocalArtifactIdentityResolver.MANIFEST_FILE_NAME}"))
        }
    }

    @Test
    fun cancellationDuringLegacyHashingIsRethrownAndLeavesNoManifestOrPart() = runTest {
        withRoot { storage, root ->
            val main = write(root / "owner/model/model.gguf", byteArrayOf(1, 2, 3))
            val expected = CancellationException("stop")
            val resolver = LocalArtifactIdentityResolver(
                storagePathProvider = storage,
                manifestSource = { null },
                hashingDispatcher = StandardTestDispatcher(testScheduler),
                fileSystem = FileSystem.SYSTEM,
                hashFile = { _, _ -> throw expected },
            )

            try {
                resolver.resolve(model(main, 3), emptyList())
                fail("Expected cancellation")
            } catch (actual: CancellationException) {
                assertTrue(actual === expected)
            }

            val manifest = root / "owner/model/${LocalArtifactIdentityResolver.MANIFEST_FILE_NAME}"
            assertFalse(FileSystem.SYSTEM.exists(manifest))
            assertFalse(FileSystem.SYSTEM.exists("$manifest.part".toPath()))
        }
    }

    private fun TestScope.resolver(storage: TestStorage, manifest: ArtifactManifest?) = LocalArtifactIdentityResolver(
        storagePathProvider = storage,
        manifestSource = { manifest },
        hashingDispatcher = StandardTestDispatcher(testScheduler),
        fileSystem = FileSystem.SYSTEM,
    )

    private suspend fun withRoot(block: suspend (TestStorage, Path) -> Unit) {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "caraml-identity-${Random.nextLong()}"
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

    private fun model(path: String, size: Long, filename: String = "model.gguf") = LocalModelEntity(
        modelId = "owner/model",
        filename = filename,
        localPath = path,
        sizeBytes = size,
        downloadedAt = 1,
        author = null,
        libraryName = null,
        pipelineTag = if (filename.endsWith("gguf")) "text-generation" else "text-to-image",
    )

    private fun component(
        repo: String,
        relative: String,
        role: String,
        path: String,
        size: Long,
        id: Long = 1,
    ) = DownloadedComponentEntity(id, repo, relative, role, path, size, 1)

    private fun manifestEntry(
        role: String,
        repo: String,
        revision: String,
        relative: String,
        expectedBytes: ByteArray,
    ): ArtifactManifestEntry {
        val identity = downloadIdentity(repo, revision, relative, expectedBytes.size.toLong())
        return checkNotNull(
            ArtifactManifestEntry.create(
                logicalRole = role,
                identity = identity,
                byteCount = expectedBytes.size.toLong(),
                contentSha256 = expectedBytes.sha256(),
            ),
        )
    }

    private fun manifest(entry: ArtifactManifestEntry) = checkNotNull(ArtifactManifest.create(listOf(entry)))

    private fun downloadIdentity(repo: String, revision: String, relative: String, size: Long) =
        checkNotNull(DownloadArtifactIdentity.create(repo, revision, relative, null, size))

    private fun ByteArray.sha256(): String = Buffer().write(this).snapshot().sha256().hex()

    private class TestStorage(private val root: Path) : StoragePathProvider {
        val unreadable = mutableSetOf<String>()

        override fun getModelsStorageDirectory(modelId: String): String = (root / modelId).toString()
        override fun getDatabasePath(): String = (root / "db").toString()
        override fun fileExists(path: String): Boolean = FileSystem.SYSTEM.exists(path.toPath())
        override fun getAvailableStorageBytes(): Long = Long.MAX_VALUE
        override fun getTotalStorageBytes(): Long = Long.MAX_VALUE
        override fun isModelFileReadable(path: String): Boolean = inspect(path)?.kind == StoredArtifactKind.REGULAR_FILE
        override fun isDirectoryReadable(path: String): Boolean = inspect(path)?.kind == StoredArtifactKind.DIRECTORY
        override fun getFileSize(path: String): Long = inspect(path)?.byteCount ?: 0
        override fun renameFile(from: String, to: String): Boolean = false
        override fun deleteDownloadedModelContent(modelId: String, localPath: String): Boolean = false

        override fun inspectDownloadedArtifact(modelId: String, localPath: String): StoredArtifactSnapshot? {
            if (localPath in unreadable) return null
            val expectedRoot = (root / modelId).normalized()
            val target = localPath.toPath().normalized()
            if (target != expectedRoot && !target.toString().startsWith("$expectedRoot/")) return null
            return inspect(localPath)
        }

        private fun inspect(path: String): StoredArtifactSnapshot? {
            val value = path.toPath()
            val metadata = FileSystem.SYSTEM.metadataOrNull(value) ?: return null
            if (metadata.symlinkTarget != null) return null
            val kind = when {
                metadata.isRegularFile -> StoredArtifactKind.REGULAR_FILE
                metadata.isDirectory -> StoredArtifactKind.DIRECTORY
                else -> return null
            }
            return StoredArtifactSnapshot(
                kind = kind,
                byteCount = metadata.size ?: 0,
                changeStamp = "${metadata.lastModifiedAtMillis ?: 0}:${metadata.size ?: 0}",
            )
        }
    }
}
