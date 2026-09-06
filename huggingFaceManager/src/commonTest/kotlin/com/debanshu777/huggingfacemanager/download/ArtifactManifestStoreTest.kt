package com.debanshu777.huggingfacemanager.download

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtifactManifestStoreTest {
    @Test
    fun immutableIdentityRejectsBlankMutableMalformedAndInvalidObjectValues() {
        val valid = identity()

        assertNotNull(valid)
        assertNull(identityOrNull(revision = ""))
        assertNull(identityOrNull(revision = "main"))
        assertNull(identityOrNull(revision = "z".repeat(40)))
        assertNull(identityOrNull(repositoryId = "../model"))
        assertNull(identityOrNull(relativePath = "../model.gguf"))
        assertNull(identityOrNull(remoteObjectId = "sha256:${"z".repeat(64)}"))
        assertNull(identityOrNull(expectedBytes = 0L))
        assertFailsWith<IllegalArgumentException> {
            DownloadMetadataDTO(
                artifact = valid,
                logicalRole = "invalid role",
                sizeBytes = valid.expectedBytes,
                author = null,
                libraryName = null,
                pipelineTag = null,
            )
        }
    }

    @Test
    fun manifestRejectsTooManyEntriesDuplicateRolesAndDuplicatePaths() {
        val entries = (0 until 65).map { index ->
            entry(
                identity(relativePath = "part-$index.gguf"),
                logicalRole = "part-$index",
                digest = index.toString(16).padStart(64, '0'),
            )
        }
        assertNull(ArtifactManifest.create(entries))

        val first = entry(identity(relativePath = "a.gguf"), "model", "a".repeat(64))
        val duplicateRole = entry(identity(relativePath = "b.gguf"), "model", "b".repeat(64))
        val duplicatePath = entry(identity(relativePath = "a.gguf"), "other", "c".repeat(64))
        assertNull(ArtifactManifest.create(listOf(first, duplicateRole)))
        assertNull(ArtifactManifest.create(listOf(first, duplicatePath)))
    }

    @Test
    fun oversizedDecodedManifestIsUnavailableEvidence() = withStore { fs, root, store ->
        fs.write(root / ArtifactManifestStore.MANIFEST_FILE_NAME) {
            write(ByteArray(ArtifactManifestStore.MAX_MANIFEST_BYTES + 1) { 'x'.code.toByte() })
        }

        assertNull(store.read())
    }

    @Test
    fun commitRejectsDigestAndByteCountMismatchWithoutPublishing() = withStore { fs, root, store ->
        val bytes = "new-generation".encodeToByteArray()
        val target = root / "model.gguf"
        fs.write(target.siblingPart()) { write(bytes) }

        assertFailsWith<ArtifactVerificationException> {
            store.commit(
                relativePath = "model.gguf",
                entry = entry(identity(expectedBytes = bytes.size.toLong()), "model", "0".repeat(64)),
            )
        }
        assertFalse(fs.exists(target))
        assertNull(store.read())

        fs.write(target.siblingPart()) { write(bytes) }
        assertFailsWith<ArtifactVerificationException> {
            store.commit(
                relativePath = "model.gguf",
                entry = entry(
                    identity(expectedBytes = bytes.size.toLong() + 1L),
                    "model",
                    bytes.sha256Hex(),
                    byteCount = bytes.size.toLong() + 1L,
                ),
            )
        }
        assertFalse(fs.exists(target))
        assertNull(store.read())
    }

    @Test
    fun canonicalBundleDigestIgnoresInputOrderAndLocalRoot() {
        val entries = listOf(
            entry(identity(relativePath = "weights/model.gguf"), "model", "a".repeat(64)),
            entry(
                identity(repositoryId = "org/vae", relativePath = "vae/model.safetensors"),
                "vae",
                "b".repeat(64),
            ),
        )

        val first = assertNotNull(ArtifactManifest.create(entries))
        val reordered = assertNotNull(ArtifactManifest.create(entries.reversed()))

        assertEquals(first.bundleDigest, reordered.bundleDigest)
        assertEquals(first.entries, reordered.entries)
        assertNotEquals("/private/models", first.bundleDigest)
    }

    @Test
    fun successfulReplacementPublishesManifestAndRemovesPreviousOnlyAfterNewGenerationValidates() =
        withStore { fs, root, store ->
            val target = root / "model.gguf"
            val old = "old".encodeToByteArray()
            stageAndCommit(fs, store, target, old, revision = "a".repeat(40))
            val new = "new-generation".encodeToByteArray()
            fs.write(target.siblingPart()) { write(new) }

            store.commit(
                relativePath = "model.gguf",
                entry = entry(
                    identity(revision = "b".repeat(40), expectedBytes = new.size.toLong()),
                    "model",
                    new.sha256Hex(),
                ),
            )

            assertEquals("new-generation", fs.read(target) { readUtf8() })
            assertEquals("b".repeat(40), store.read()?.entries?.single()?.identity?.immutableRevision)
            assertFalse(fs.exists(target.siblingPrevious()))
            assertFalse(fs.exists(root / ArtifactManifestStore.JOURNAL_FILE_NAME))
        }

    @Test
    fun restartAfterEveryJournalPhaseFinishesTheValidNewGeneration() {
        ManifestJournalPhase.entries.forEach { crashPhase ->
            val fs = FakeFileSystem()
            val root = "/models/org/model-${crashPhase.name}".toPath()
            fs.createDirectories(root)
            val target = root / "model.gguf"
            val initial = ArtifactManifestStore(root, fs)
            stageAndCommit(fs, initial, target, "old".encodeToByteArray(), revision = "a".repeat(40))
            val replacement = "replacement-${crashPhase.name}".encodeToByteArray()
            fs.write(target.siblingPart()) { write(replacement) }
            val crashing = ArtifactManifestStore(root, fs) { phase ->
                if (phase == crashPhase) throw SimulatedCrash()
            }

            assertFailsWith<SimulatedCrash> {
                crashing.commit(
                    relativePath = "model.gguf",
                    entry = entry(
                        identity(revision = "b".repeat(40), expectedBytes = replacement.size.toLong()),
                        "model",
                        replacement.sha256Hex(),
                    ),
                )
            }

            val restarted = ArtifactManifestStore(root, fs)
            restarted.recover()
            assertEquals(replacement.decodeToString(), fs.read(target) { readUtf8() }, crashPhase.name)
            assertEquals("b".repeat(40), restarted.read()?.entries?.single()?.identity?.immutableRevision)
            assertFalse(fs.exists(root / ArtifactManifestStore.JOURNAL_FILE_NAME))
        }
    }

    @Test
    fun restartAfterEveryJournalPhaseRestoresOldWhenNewGenerationIsInvalid() {
        ManifestJournalPhase.entries.forEach { crashPhase ->
            val fs = FakeFileSystem()
            val root = "/models/org/restore-${crashPhase.name}".toPath()
            fs.createDirectories(root)
            val target = root / "model.gguf"
            val old = "old-generation".encodeToByteArray()
            stageAndCommit(fs, ArtifactManifestStore(root, fs), target, old, revision = "a".repeat(40))
            val replacement = "replacement-${crashPhase.name}".encodeToByteArray()
            fs.write(target.siblingPart()) { write(replacement) }
            val crashing = ArtifactManifestStore(root, fs) { phase ->
                if (phase == crashPhase) throw SimulatedCrash()
            }
            assertFailsWith<SimulatedCrash> {
                crashing.commit(
                    "model.gguf",
                    entry(
                        identity(revision = "b".repeat(40), expectedBytes = replacement.size.toLong()),
                        "model",
                        replacement.sha256Hex(),
                    ),
                )
            }
            val newPath = if (crashPhase == ManifestJournalPhase.NEW_PUBLISHED) target else target.siblingPart()
            fs.write(newPath) { writeUtf8("tampered") }

            val restarted = ArtifactManifestStore(root, fs)
            restarted.recover()

            assertEquals(old.decodeToString(), fs.read(target) { readUtf8() }, crashPhase.name)
            assertEquals("a".repeat(40), restarted.read()?.entries?.single()?.identity?.immutableRevision)
            assertFalse(fs.exists(root / ArtifactManifestStore.JOURNAL_FILE_NAME))
        }
    }
}

private class SimulatedCrash : RuntimeException()

private fun withStore(block: (FakeFileSystem, Path, ArtifactManifestStore) -> Unit) {
    val fs = FakeFileSystem()
    val root = "/models/org/model".toPath()
    fs.createDirectories(root)
    block(fs, root, ArtifactManifestStore(root, fs))
}

private fun stageAndCommit(
    fs: FileSystem,
    store: ArtifactManifestStore,
    target: Path,
    bytes: ByteArray,
    revision: String,
) {
    fs.write(target.siblingPart()) { write(bytes) }
    store.commit(
        relativePath = target.name,
        entry = entry(
            identity(revision = revision, expectedBytes = bytes.size.toLong()),
            logicalRole = "model",
            digest = bytes.sha256Hex(),
        ),
    )
}

private fun identity(
    repositoryId: String = "org/model",
    revision: String = "a".repeat(40),
    relativePath: String = "model.gguf",
    remoteObjectId: String? = null,
    expectedBytes: Long = 3L,
): DownloadArtifactIdentity = requireNotNull(
    DownloadArtifactIdentity.create(
        repositoryId = repositoryId,
        immutableRevision = revision,
        relativePath = relativePath,
        remoteObjectId = remoteObjectId,
        expectedBytes = expectedBytes,
    ),
)

private fun identityOrNull(
    repositoryId: String = "org/model",
    revision: String = "a".repeat(40),
    relativePath: String = "model.gguf",
    remoteObjectId: String? = "sha256:${"c".repeat(64)}",
    expectedBytes: Long = 3L,
): DownloadArtifactIdentity? = DownloadArtifactIdentity.create(
    repositoryId = repositoryId,
    immutableRevision = revision,
    relativePath = relativePath,
    remoteObjectId = remoteObjectId,
    expectedBytes = expectedBytes,
)

private fun entry(
    identity: DownloadArtifactIdentity,
    logicalRole: String,
    digest: String,
    byteCount: Long = identity.expectedBytes,
): ArtifactManifestEntry = requireNotNull(
    ArtifactManifestEntry.create(
        logicalRole = logicalRole,
        identity = identity,
        byteCount = byteCount,
        contentSha256 = digest,
    ),
)

private fun Path.siblingPart(): Path = "$this.part".toPath()
private fun Path.siblingPrevious(): Path = "$this.previous".toPath()

private fun ByteArray.sha256Hex(): String = okio.ByteString.of(*this).sha256().hex()
