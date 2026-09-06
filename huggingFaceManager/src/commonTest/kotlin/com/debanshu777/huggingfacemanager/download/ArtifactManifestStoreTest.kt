package com.debanshu777.huggingfacemanager.download

import okio.FileSystem
import okio.ForwardingFileSystem
import okio.ForwardingSource
import okio.Path
import okio.Path.Companion.toPath
import okio.Source
import okio.Buffer
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
    fun repeatedRecoveryAfterPartialPreservationNeverDeletesTheOnlyOldGeneration() {
        val fs = FakeFileSystem()
        val root = "/models/org/partial-preserve".toPath()
        fs.createDirectories(root)
        val target = root / "model.gguf"
        val old = "old-generation".encodeToByteArray()
        stageAndCommit(fs, ArtifactManifestStore(root, fs), target, old, revision = "a".repeat(40))
        val replacement = "replacement-generation".encodeToByteArray()
        fs.write(target.siblingPart()) { write(replacement) }

        val crashAfterTargetPreserved = FaultAfterMutationFileSystem(fs) { _, from, to ->
            from == target && to == target.siblingPrevious()
        }
        assertFailsWith<SimulatedCrash> {
            ArtifactManifestStore(root, crashAfterTargetPreserved).commit(
                "model.gguf",
                entry(
                    identity(revision = "b".repeat(40), expectedBytes = replacement.size.toLong()),
                    "model",
                    replacement.sha256Hex(),
                ),
            )
        }

        fs.write(target.siblingPart()) { writeUtf8("tampered") }

        repeat(3) { ArtifactManifestStore(root, fs).recover() }

        assertEquals(old.decodeToString(), fs.read(target) { readUtf8() })
        assertEquals("a".repeat(40), ArtifactManifestStore(root, fs).read()?.entries?.single()?.identity?.immutableRevision)
    }

    @Test
    fun everyTransactionMutationCanCrashAndRepeatedRecoveryKeepsOneValidGeneration() {
        val mutationCount = successfulReplacementMutationCount()
        assertTrue(mutationCount > 0)

        repeat(mutationCount) { crashIndex ->
            val fs = FakeFileSystem()
            val root = "/models/org/fault-$crashIndex".toPath()
            fs.createDirectories(root)
            val target = root / "model.gguf"
            stageAndCommit(fs, ArtifactManifestStore(root, fs), target, "old".encodeToByteArray(), "a".repeat(40))
            val replacement = "replacement-$crashIndex".encodeToByteArray()
            fs.write(target.siblingPart()) { write(replacement) }
            val faulting = CountingMutationFileSystem(fs, crashIndex)

            runCatching {
                ArtifactManifestStore(root, faulting).commit(
                    "model.gguf",
                    entry(
                        identity(revision = "b".repeat(40), expectedBytes = replacement.size.toLong()),
                        "model",
                        replacement.sha256Hex(),
                    ),
                )
            }
            repeat(3) { ArtifactManifestStore(root, fs).recover() }

            val manifest = assertNotNull(ArtifactManifestStore(root, fs).read(), "fault $crashIndex")
            val installed = fs.read(target) { readByteArray() }
            assertTrue(installed.contentEquals("old".encodeToByteArray()) || installed.contentEquals(replacement))
            assertEquals(installed.sha256Hex(), manifest.entries.single().contentSha256)
            assertFalse(fs.exists(root / ArtifactManifestStore.JOURNAL_FILE_NAME))
        }
    }

    @Test
    fun commitOrdersDurableFileAndDirectoryBarriersBeforeTerminalReturn() {
        val fs = FakeFileSystem()
        val root = "/models/org/durable".toPath()
        fs.createDirectories(root)
        val target = root / "model.gguf"
        val bytes = "durable".encodeToByteArray()
        fs.write(target.siblingPart()) { write(bytes) }
        val durability = RecordingArtifactDurability()

        ArtifactManifestStore(root, fs, durability = durability).commit(
            "model.gguf",
            entry(identity(expectedBytes = bytes.size.toLong()), "model", bytes.sha256Hex()),
        )

        assertTrue(durability.events.indexOf("file:.caraml-artifact-v1.json.part") < durability.events.indexOf("dir:."))
        assertTrue(durability.events.count { it == "dir:." } >= 4)
        assertEquals("dir:.", durability.events.last())
    }

    @Test
    fun durabilityFailureAfterJournalRemovalFailsClosed() {
        val baselineFs = FakeFileSystem()
        val baselineRoot = "/models/org/durable-baseline".toPath()
        baselineFs.createDirectories(baselineRoot)
        val baselineBytes = "durable".encodeToByteArray()
        baselineFs.write((baselineRoot / "model.gguf").siblingPart()) { write(baselineBytes) }
        val baseline = RecordingArtifactDurability()
        ArtifactManifestStore(baselineRoot, baselineFs, durability = baseline).commit(
            "model.gguf",
            entry(identity(expectedBytes = baselineBytes.size.toLong()), "model", baselineBytes.sha256Hex()),
        )
        val terminalDirectoryBarrier = baseline.events.count { it.startsWith("dir:") }

        val fs = FakeFileSystem()
        val root = "/models/org/durable-failure".toPath()
        fs.createDirectories(root)
        val target = root / "model.gguf"
        val bytes = "durable".encodeToByteArray()
        fs.write(target.siblingPart()) { write(bytes) }
        val durability = RecordingArtifactDurability(failAtDirectorySync = terminalDirectoryBarrier)

        assertFailsWith<ArtifactDurabilityException> {
            ArtifactManifestStore(root, fs, durability = durability).commit(
                "model.gguf",
                entry(identity(expectedBytes = bytes.size.toLong()), "model", bytes.sha256Hex()),
            )
        }
    }

    @Test
    fun boundedManifestReadNeverConsumesMoreThanLimitPlusOneAfterAFileSwap() {
        val fs = FakeFileSystem()
        val root = "/models/org/bounded-read".toPath()
        fs.createDirectories(root)
        val manifestPath = root / ArtifactManifestStore.MANIFEST_FILE_NAME
        fs.write(manifestPath) { writeUtf8("{}") }
        val swapping = SwapOnSourceFileSystem(fs, manifestPath)

        assertNull(ArtifactManifestStore(root, swapping).read())

        assertTrue(swapping.bytesRead <= ArtifactManifestStore.MAX_MANIFEST_BYTES + 1L)
    }

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
        assertNull(identityOrNull(remoteObjectId = "sha256:${"a".repeat(63)}"))
        assertNull(identityOrNull(remoteObjectId = "sha256:${"a".repeat(65)}"))
        assertNotNull(identityOrNull(remoteObjectId = "a".repeat(40)))
        assertNotNull(identityOrNull(remoteObjectId = "b".repeat(128)))
        assertNull(identityOrNull(remoteObjectId = "git:${"a".repeat(40)}"))
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
        val duplicateIdentity = identity(relativePath = "b.gguf")
        val duplicateRole = requireNotNull(
            ArtifactManifestEntry.create(
                "model",
                duplicateIdentity,
                duplicateIdentity.expectedBytes,
                "b".repeat(64),
                bundleId = first.bundleId,
            ),
        )
        val duplicatePath = entry(identity(relativePath = "a.gguf"), "other", "c".repeat(64))
        assertNull(ArtifactManifest.create(listOf(first, duplicateRole)))
        assertNull(ArtifactManifest.create(listOf(first, duplicatePath)))
    }

    @Test
    fun roleUniquenessIsScopedToOwningBundleAndNormalizedDestinationIsValidated() = withStore { fs, root, store ->
        val standardPath = "unet/diffusion_pytorch_model.safetensors"
        fs.createDirectories(root / "unet")
        val bytes = "fp16-content".encodeToByteArray()
        fs.write((root / standardPath).siblingPart()) { write(bytes) }
        val remote = identity(
            relativePath = "unet/diffusion_pytorch_model.fp16.safetensors",
            expectedBytes = bytes.size.toLong(),
        )
        val first = requireNotNull(
            ArtifactManifestEntry.create(
                logicalRole = "model",
                identity = remote,
                byteCount = bytes.size.toLong(),
                contentSha256 = bytes.sha256Hex(),
                bundleId = "1".repeat(64),
                localRelativePath = standardPath,
            ),
        )

        store.commit(standardPath, first)

        assertEquals(standardPath, store.readValidated()?.entries?.single()?.localRelativePath)
        val secondBytes = "two".encodeToByteArray()
        val second = requireNotNull(
            ArtifactManifestEntry.create(
                logicalRole = "model",
                identity = identity(relativePath = "other.gguf", expectedBytes = secondBytes.size.toLong()),
                byteCount = secondBytes.size.toLong(),
                contentSha256 = secondBytes.sha256Hex(),
                bundleId = "2".repeat(64),
                localRelativePath = "other.gguf",
            ),
        )
        assertNotNull(ArtifactManifest.create(listOf(first, second)))
        val duplicateRoleSameBundle = requireNotNull(
            ArtifactManifestEntry.create(
                logicalRole = "model",
                identity = second.identity,
                byteCount = second.byteCount,
                contentSha256 = second.contentSha256,
                bundleId = "1".repeat(64),
                localRelativePath = second.localRelativePath,
            ),
        )
        assertNull(ArtifactManifest.create(listOf(first, duplicateRoleSameBundle)))
        fs.write((root / "other.gguf").siblingPart()) { write(secondBytes) }
        store.commit("other.gguf", second)
        assertEquals(2, store.readValidated()?.entries?.size)
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
    fun canonicalBundleDigestIgnoresInputOrderButBindsNormalizedDestination() {
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
        val relocatedEntry = requireNotNull(
            ArtifactManifestEntry.create(
                logicalRole = entries.first().logicalRole,
                identity = entries.first().identity,
                byteCount = entries.first().byteCount,
                contentSha256 = entries.first().contentSha256,
                bundleId = entries.first().bundleId,
                localRelativePath = "local/renamed.gguf",
            ),
        )
        val relocated = assertNotNull(ArtifactManifest.create(listOf(relocatedEntry, entries.last())))

        assertEquals(first.bundleDigest, reordered.bundleDigest)
        assertNotEquals(first.bundleDigest, relocated.bundleDigest)
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

    @Test
    fun tamperedJournalTupleCannotAuthorizeDeletionOfThePublishedGeneration() {
        val tamperers: List<(String) -> String> = listOf(
            { journal -> journal.replace("\"version\":1", "\"version\":2") },
            { journal -> journal.replace("\"relativePath\":\"model.gguf\"", "\"relativePath\":\"other.gguf\"") },
            { journal -> journal.replace("\"transactionId\":\"", "\"transactionId\":\"f") },
            { journal -> journal.replace("\"previousManifestDigest\":\"", "\"previousManifestDigest\":\"f") },
            { journal -> journal.replace("\"previousTargetSha256\":\"", "\"previousTargetSha256\":\"f") },
            { journal -> journal.replace("\"phase\":\"PREPARED\"", "\"phase\":\"NOT_A_PHASE\"") },
            // A PREPARED filesystem cannot truthfully claim terminal cleanup state.
            { journal -> journal
                .replace("\"phase\":\"PREPARED\"", "\"phase\":\"NEW_PUBLISHED\"")
                .replace("\"hadPreviousTarget\":true", "\"hadPreviousTarget\":false")
                .replace("\"hadPreviousManifest\":true", "\"hadPreviousManifest\":false")
                .replace("\"step\":\"PREPARED\"", "\"step\":\"OLD_MANIFEST_REMOVED\"") },
            // The phase/step pair itself is illegal even if the previous-generation flags are true.
            { journal -> journal.replace("\"step\":\"PREPARED\"", "\"step\":\"NEW_TARGET_PUBLISHED\"") },
            // A caller-controlled legacy aggregate boolean cannot override concrete old-generation evidence.
            { journal -> journal
                .replace("\"phase\":\"PREPARED\"", "\"phase\":\"OLD_PRESERVED\"")
                .replace("\"hadPreviousTarget\":true,", "")
                .replace("\"hadPreviousManifest\":true,", "")
                .replace("\"hadPreviousGeneration\":null", "\"hadPreviousGeneration\":false")
                .replace("\"step\":\"PREPARED\"", "\"step\":\"OLD_PRESERVED\"") },
        )

        tamperers.forEachIndexed { index, tamper ->
            val fs = FakeFileSystem()
            val root = "/models/org/tampered-$index".toPath()
            fs.createDirectories(root)
            val target = root / "model.gguf"
            val old = "known-valid-old-$index".encodeToByteArray()
            stageAndCommit(fs, ArtifactManifestStore(root, fs), target, old, revision = "a".repeat(40))
            val replacement = "new-$index".encodeToByteArray()
            fs.write(target.siblingPart()) { write(replacement) }
            val crashing = ArtifactManifestStore(root, fs) { phase ->
                if (phase == ManifestJournalPhase.PREPARED) throw SimulatedCrash()
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
            val journalPath = root / ArtifactManifestStore.JOURNAL_FILE_NAME
            val originalJournal = fs.read(journalPath) { readUtf8() }
            fs.write(journalPath) { writeUtf8(tamper(originalJournal)) }

            repeat(3) { runCatching { ArtifactManifestStore(root, fs).recover() } }

            assertTrue(fs.exists(target), "case $index removed the only published target")
            assertEquals(old.decodeToString(), fs.read(target) { readUtf8() }, "case $index")
            assertEquals(
                "a".repeat(40),
                ArtifactManifestStore(root, fs).readValidated()?.entries?.single()?.identity?.immutableRevision,
                "case $index",
            )
        }
    }
}

private class SimulatedCrash : RuntimeException()

private enum class MutationKind { DELETE, MOVE }

private class FaultAfterMutationFileSystem(
    delegate: FileSystem,
    private val shouldCrash: (MutationKind, Path, Path?) -> Boolean,
) : ForwardingFileSystem(delegate) {
    override fun atomicMove(source: Path, target: Path) {
        super.atomicMove(source, target)
        if (shouldCrash(MutationKind.MOVE, source, target)) throw SimulatedCrash()
    }

    override fun delete(path: Path, mustExist: Boolean) {
        super.delete(path, mustExist)
        if (shouldCrash(MutationKind.DELETE, path, null)) throw SimulatedCrash()
    }
}

private class CountingMutationFileSystem(
    delegate: FileSystem,
    private val crashIndex: Int?,
) : ForwardingFileSystem(delegate) {
    var count: Int = 0

    override fun atomicMove(source: Path, target: Path) {
        val transactionActive = delegate.exists(source.parent!! / ArtifactManifestStore.JOURNAL_FILE_NAME) ||
            source.name.contains("journal")
        super.atomicMove(source, target)
        if (transactionActive && count++ == crashIndex) throw SimulatedCrash()
    }

    override fun delete(path: Path, mustExist: Boolean) {
        val transactionActive = delegate.exists(path.parent!! / ArtifactManifestStore.JOURNAL_FILE_NAME)
        super.delete(path, mustExist)
        if (transactionActive && count++ == crashIndex) throw SimulatedCrash()
    }
}

private class SwapOnSourceFileSystem(
    private val fs: FileSystem,
    private val swappedPath: Path,
) : ForwardingFileSystem(fs) {
    var bytesRead: Long = 0L

    override fun source(file: Path): Source {
        if (file == swappedPath) {
            fs.write(file) { write(ByteArray(ArtifactManifestStore.MAX_MANIFEST_BYTES + 4_096) { 'x'.code.toByte() }) }
        }
        return object : ForwardingSource(super.source(file)) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                val read = super.read(sink, byteCount)
                if (read > 0L) bytesRead += read
                return read
            }
        }
    }
}

private class RecordingArtifactDurability(
    private val failAtDirectorySync: Int? = null,
) : ArtifactDurability {
    val events = mutableListOf<String>()
    private var directorySyncCount = 0

    override fun syncFile(relativePath: String) {
        events += "file:$relativePath"
    }

    override fun syncDirectory(relativePath: String) {
        events += "dir:$relativePath"
        directorySyncCount++
        if (directorySyncCount == failAtDirectorySync) throw ArtifactDurabilityException()
    }
}

private fun successfulReplacementMutationCount(): Int {
    val fs = FakeFileSystem()
    val root = "/models/org/count".toPath()
    fs.createDirectories(root)
    val target = root / "model.gguf"
    stageAndCommit(fs, ArtifactManifestStore(root, fs), target, "old".encodeToByteArray(), "a".repeat(40))
    val replacement = "replacement".encodeToByteArray()
    fs.write(target.siblingPart()) { write(replacement) }
    val counting = CountingMutationFileSystem(fs, crashIndex = null)
    ArtifactManifestStore(root, counting).commit(
        "model.gguf",
        entry(
            identity(revision = "b".repeat(40), expectedBytes = replacement.size.toLong()),
            "model",
            replacement.sha256Hex(),
        ),
    )
    return counting.count
}

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
