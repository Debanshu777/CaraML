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
    fun pruningOneImmutableGenerationKeepsTheOtherRevisionValidAfterReopen() =
        withStore { fs, root, store ->
            val firstBytes = "first-revision".encodeToByteArray()
            val secondBytes = "second-revision".encodeToByteArray()
            val first = scopedEntry(
                identity(
                    revision = "a".repeat(40),
                    remoteObjectId = "sha256:${firstBytes.sha256Hex()}",
                    expectedBytes = firstBytes.size.toLong(),
                ),
                bundleId = "1".repeat(64),
                bytes = firstBytes,
            )
            val second = scopedEntry(
                identity(
                    revision = "b".repeat(40),
                    remoteObjectId = "sha256:${secondBytes.sha256Hex()}",
                    expectedBytes = secondBytes.size.toLong(),
                ),
                bundleId = "2".repeat(64),
                bytes = secondBytes,
            )
            listOf(first to firstBytes, second to secondBytes).forEach { (entry, bytes) ->
                val target = root / entry.localRelativePath
                fs.createDirectories(requireNotNull(target.parent))
                fs.write(target.siblingPart()) { write(bytes) }
                store.commit(entry.localRelativePath, entry)
            }

            assertTrue(store.pruneValidated(listOf(first)))
            fs.delete(root / first.localRelativePath)

            val reopened = ArtifactManifestStore(root, fs)
            reopened.recover()
            assertEquals(listOf(second), reopened.readValidated()?.entries)
            assertTrue(fs.exists(root / second.localRelativePath))
        }

    @Test
    fun restartAfterEveryPrunePhaseKeepsTheRetainedRevisionValid() {
        ManifestJournalPhase.entries.filterNot { it == ManifestJournalPhase.ROLLING_BACK }.forEach { crashPhase ->
            val fs = FakeFileSystem()
            val root = "/models/org/prune-${crashPhase.name}".toPath()
            fs.createDirectories(root)
            val firstBytes = "first-${crashPhase.name}".encodeToByteArray()
            val secondBytes = "second-${crashPhase.name}".encodeToByteArray()
            val first = scopedEntry(
                identity(
                    revision = "a".repeat(40),
                    remoteObjectId = "sha256:${firstBytes.sha256Hex()}",
                    expectedBytes = firstBytes.size.toLong(),
                ),
                bundleId = "1".repeat(64),
                bytes = firstBytes,
            )
            val second = scopedEntry(
                identity(
                    revision = "b".repeat(40),
                    remoteObjectId = "sha256:${secondBytes.sha256Hex()}",
                    expectedBytes = secondBytes.size.toLong(),
                ),
                bundleId = "2".repeat(64),
                bytes = secondBytes,
            )
            val initial = ArtifactManifestStore(root, fs)
            listOf(first to firstBytes, second to secondBytes).forEach { (entry, bytes) ->
                installScopedEntry(fs, root, initial, entry, bytes)
            }
            val crashing = ArtifactManifestStore(root, fs, phaseObserver = { phase ->
                if (phase == crashPhase) throw SimulatedCrash()
            })

            assertFailsWith<SimulatedCrash> { crashing.pruneValidated(listOf(first)) }

            val reopened = ArtifactManifestStore(root, fs)
            repeat(2) { reopened.recover() }
            fs.delete(root / first.localRelativePath)
            assertEquals(listOf(second), reopened.readValidated()?.entries, crashPhase.name)
            assertFalse(fs.exists(root / ArtifactManifestStore.PRUNE_JOURNAL_FILE_NAME), crashPhase.name)
        }
    }

    @Test
    fun repeatedRecoveryAfterPartialPreservationNeverDeletesTheOnlyOldGeneration() {
        val fs = FakeFileSystem()
        val root = "/models/org/partial-preserve".toPath()
        fs.createDirectories(root)
        val old = "old-generation".encodeToByteArray()
        val target = stageAndCommit(fs, ArtifactManifestStore(root, fs), root, old, revision = "a".repeat(40))
        val replacement = "new-generation".encodeToByteArray()
        fs.write(target.siblingPart()) { write(replacement) }

        val crashAfterTargetPreserved = FaultAfterMutationFileSystem(fs) { _, from, to ->
            from == target && to == target.siblingPrevious()
        }
        val replacementEntry = entry(
            identity(revision = "a".repeat(40), expectedBytes = replacement.size.toLong()),
            "model",
            replacement.sha256Hex(),
        )
        assertFailsWith<SimulatedCrash> {
            ArtifactManifestStore(root, crashAfterTargetPreserved).commit(
                replacementEntry.localRelativePath,
                replacementEntry,
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
            val replacement = "replacement-$crashIndex".encodeToByteArray()
            val old = ByteArray(replacement.size) { 'o'.code.toByte() }
            val artifact = identity(expectedBytes = replacement.size.toLong())
            val bundleId = requireNotNull(artifactBundleId(listOf(artifact)))
            val replacementEntry = scopedEntry(artifact, bundleId, replacement)
            val target = root / replacementEntry.localRelativePath
            installScopedEntry(
                fs = fs,
                root = root,
                store = ArtifactManifestStore(root, fs),
                entry = scopedEntry(artifact, bundleId, old),
                bytes = old,
            )
            fs.write(target.siblingPart()) { write(replacement) }
            val faulting = CountingMutationFileSystem(fs, crashIndex)

            runCatching {
                ArtifactManifestStore(root, faulting).commit(
                    replacementEntry.localRelativePath,
                    replacementEntry,
                )
            }
            repeat(3) { ArtifactManifestStore(root, fs).recover() }

            val manifest = assertNotNull(ArtifactManifestStore(root, fs).read(), "fault $crashIndex")
            val installed = fs.read(target) { readByteArray() }
            assertTrue(installed.contentEquals(old) || installed.contentEquals(replacement))
            assertEquals(installed.sha256Hex(), manifest.entries.single().contentSha256)
            assertFalse(fs.exists(root / ArtifactManifestStore.JOURNAL_FILE_NAME))
        }
    }

    @Test
    fun everyRollbackMutationCanCrashAndRepeatedRecoveryRestoresTheValidatedOldGeneration() {
        val mutationCount = successfulRollbackMutationCount()
        assertTrue(mutationCount > 0)

        repeat(mutationCount) { crashIndex ->
            val fs = FakeFileSystem()
            val root = "/models/org/rollback-$crashIndex".toPath()
            val target = prepareInvalidPublishedReplacement(fs, root)
            val faulting = CountingMutationFileSystem(fs, crashIndex)

            runCatching { ArtifactManifestStore(root, faulting).recover() }
            repeat(3) { runCatching { ArtifactManifestStore(root, fs).recover() } }

            val restored = assertNotNull(
                ArtifactManifestStore(root, fs).readValidated(),
                "rollback mutation $crashIndex",
            )
            assertEquals("a".repeat(40), restored.entries.single().identity.immutableRevision)
            assertEquals("old-generation", fs.read(target) { readUtf8() })
            assertFalse(fs.exists(root / ArtifactManifestStore.JOURNAL_FILE_NAME))
        }
    }

    @Test
    fun commitOrdersDurableFileAndDirectoryBarriersBeforeTerminalReturn() {
        val fs = FakeFileSystem()
        val root = "/models/org/durable".toPath()
        fs.createDirectories(root)
        val bytes = "durable".encodeToByteArray()
        val manifestEntry = entry(identity(expectedBytes = bytes.size.toLong()), "model", bytes.sha256Hex())
        val target = root / manifestEntry.localRelativePath
        fs.createDirectories(requireNotNull(target.parent))
        fs.write(target.siblingPart()) { write(bytes) }
        val durability = RecordingArtifactDurability()

        ArtifactManifestStore(root, fs, durability = durability).commit(
            manifestEntry.localRelativePath,
            manifestEntry,
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
        val baselineEntry = entry(
            identity(expectedBytes = baselineBytes.size.toLong()),
            "model",
            baselineBytes.sha256Hex(),
        )
        val baselineTarget = baselineRoot / baselineEntry.localRelativePath
        baselineFs.createDirectories(requireNotNull(baselineTarget.parent))
        baselineFs.write(baselineTarget.siblingPart()) { write(baselineBytes) }
        val baseline = RecordingArtifactDurability()
        ArtifactManifestStore(baselineRoot, baselineFs, durability = baseline).commit(
            baselineEntry.localRelativePath,
            baselineEntry,
        )
        val terminalDirectoryBarrier = baseline.events.count { it.startsWith("dir:") }

        val fs = FakeFileSystem()
        val root = "/models/org/durable-failure".toPath()
        fs.createDirectories(root)
        val bytes = "durable".encodeToByteArray()
        val manifestEntry = entry(identity(expectedBytes = bytes.size.toLong()), "model", bytes.sha256Hex())
        val target = root / manifestEntry.localRelativePath
        fs.createDirectories(requireNotNull(target.parent))
        fs.write(target.siblingPart()) { write(bytes) }
        val durability = RecordingArtifactDurability(failAtDirectorySync = terminalDirectoryBarrier)

        assertFailsWith<ArtifactDurabilityException> {
            ArtifactManifestStore(root, fs, durability = durability).commit(
                manifestEntry.localRelativePath,
                manifestEntry,
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
        val duplicateRoleLocation = immutableArtifactStorageLocation(duplicateIdentity, first.bundleId)
        val duplicateRole = requireNotNull(
            ArtifactManifestEntry.create(
                "model",
                duplicateIdentity,
                duplicateIdentity.expectedBytes,
                "b".repeat(64),
                bundleId = first.bundleId,
                localRelativePath = duplicateRoleLocation.localRelativePath,
                layoutRelativePath = duplicateRoleLocation.layoutRelativePath,
            ),
        )
        val duplicatePath = entry(identity(relativePath = "a.gguf"), "other", "c".repeat(64))
        assertNull(ArtifactManifest.create(listOf(first, duplicateRole)))
        assertNull(ArtifactManifest.create(listOf(first, duplicatePath)))
    }

    @Test
    fun roleUniquenessIsScopedToOwningBundleAndNormalizedDestinationIsValidated() = withStore { fs, root, store ->
        val bytes = "fp16-content".encodeToByteArray()
        val remote = identity(
            relativePath = "unet/diffusion_pytorch_model.fp16.safetensors",
            expectedBytes = bytes.size.toLong(),
        )
        val firstBundleId = "1".repeat(64)
        val firstLocation = immutableArtifactStorageLocation(remote, firstBundleId)
        val standardPath = firstLocation.localRelativePath
        fs.createDirectories(requireNotNull((root / standardPath).parent))
        fs.write((root / standardPath).siblingPart()) { write(bytes) }
        val first = requireNotNull(
            ArtifactManifestEntry.create(
                logicalRole = "model",
                identity = remote,
                byteCount = bytes.size.toLong(),
                contentSha256 = bytes.sha256Hex(),
                bundleId = firstBundleId,
                localRelativePath = standardPath,
                layoutRelativePath = firstLocation.layoutRelativePath,
            ),
        )

        store.commit(standardPath, first)

        assertEquals("unet/diffusion_pytorch_model.safetensors", first.layoutRelativePath)
        assertEquals(standardPath, store.readValidated()?.entries?.single()?.localRelativePath)
        val secondBytes = "two".encodeToByteArray()
        val secondIdentity = identity(relativePath = "other.gguf", expectedBytes = secondBytes.size.toLong())
        val secondBundleId = "2".repeat(64)
        val secondLocation = immutableArtifactStorageLocation(secondIdentity, secondBundleId)
        val second = requireNotNull(
            ArtifactManifestEntry.create(
                logicalRole = "model",
                identity = secondIdentity,
                byteCount = secondBytes.size.toLong(),
                contentSha256 = secondBytes.sha256Hex(),
                bundleId = secondBundleId,
                localRelativePath = secondLocation.localRelativePath,
                layoutRelativePath = secondLocation.layoutRelativePath,
            ),
        )
        assertNotNull(ArtifactManifest.create(listOf(first, second)))
        val duplicateRoleLocation = immutableArtifactStorageLocation(second.identity, first.bundleId)
        val duplicateRoleSameBundle = requireNotNull(
            ArtifactManifestEntry.create(
                logicalRole = "model",
                identity = second.identity,
                byteCount = second.byteCount,
                contentSha256 = second.contentSha256,
                bundleId = "1".repeat(64),
                localRelativePath = duplicateRoleLocation.localRelativePath,
                layoutRelativePath = duplicateRoleLocation.layoutRelativePath,
            ),
        )
        assertNull(ArtifactManifest.create(listOf(first, duplicateRoleSameBundle)))
        val secondTarget = root / second.localRelativePath
        fs.createDirectories(requireNotNull(secondTarget.parent))
        fs.write(secondTarget.siblingPart()) { write(secondBytes) }
        store.commit(second.localRelativePath, second)
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
        val digestMismatch = entry(identity(expectedBytes = bytes.size.toLong()), "model", "0".repeat(64))
        val target = root / digestMismatch.localRelativePath
        fs.createDirectories(requireNotNull(target.parent))
        fs.write(target.siblingPart()) { write(bytes) }

        assertFailsWith<ArtifactVerificationException> {
            store.commit(
                relativePath = digestMismatch.localRelativePath,
                entry = digestMismatch,
            )
        }
        assertFalse(fs.exists(target))
        assertNull(store.read())

        val byteCountMismatch = entry(
            identity(expectedBytes = bytes.size.toLong() + 1L),
            "model",
            bytes.sha256Hex(),
            byteCount = bytes.size.toLong() + 1L,
        )
        val mismatchedTarget = root / byteCountMismatch.localRelativePath
        fs.createDirectories(requireNotNull(mismatchedTarget.parent))
        fs.write(mismatchedTarget.siblingPart()) { write(bytes) }
        assertFailsWith<ArtifactVerificationException> {
            store.commit(
                relativePath = byteCountMismatch.localRelativePath,
                entry = byteCountMismatch,
            )
        }
        assertFalse(fs.exists(mismatchedTarget))
        assertNull(store.read())
    }

    @Test
    fun canonicalBundleDigestIgnoresInputOrderAndRejectsUnscopedDestination() {
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
        val scopedLocation = immutableArtifactStorageLocation(
            entries.first().identity,
            entries.first().bundleId,
        )
        val unscopedEntry = ArtifactManifestEntry.create(
            logicalRole = entries.first().logicalRole,
            identity = entries.first().identity,
            byteCount = entries.first().byteCount,
            contentSha256 = entries.first().contentSha256,
            bundleId = entries.first().bundleId,
            localRelativePath = scopedLocation.layoutRelativePath,
            layoutRelativePath = scopedLocation.layoutRelativePath,
        )

        assertEquals(first.bundleDigest, reordered.bundleDigest)
        assertEquals(first.entries, reordered.entries)
        assertEquals(scopedLocation.localRelativePath, entries.first().localRelativePath)
        assertNull(unscopedEntry)
        assertNotEquals("/private/models", first.bundleDigest)
    }

    @Test
    fun successfulReplacementPublishesManifestAndRemovesPreviousOnlyAfterNewGenerationValidates() =
        withStore { fs, root, store ->
            val old = "old-generation".encodeToByteArray()
            val target = stageAndCommit(fs, store, root, old, revision = "a".repeat(40))
            val new = "new-generation".encodeToByteArray()
            fs.write(target.siblingPart()) { write(new) }
            val replacementEntry = entry(
                identity(revision = "a".repeat(40), expectedBytes = new.size.toLong()),
                "model",
                new.sha256Hex(),
            )

            store.commit(
                relativePath = replacementEntry.localRelativePath,
                entry = replacementEntry,
            )

            assertEquals("new-generation", fs.read(target) { readUtf8() })
            assertEquals("a".repeat(40), store.read()?.entries?.single()?.identity?.immutableRevision)
            assertFalse(fs.exists(target.siblingPrevious()))
            assertFalse(fs.exists(root / ArtifactManifestStore.JOURNAL_FILE_NAME))
        }

    @Test
    fun restartAfterEveryJournalPhaseFinishesTheValidNewGeneration() {
        ManifestJournalPhase.entries.filterNot { it == ManifestJournalPhase.ROLLING_BACK }.forEach { crashPhase ->
            val fs = FakeFileSystem()
            val root = "/models/org/model-${crashPhase.name}".toPath()
            fs.createDirectories(root)
            val replacement = "replacement-${crashPhase.name}".encodeToByteArray()
            val replacementEntry = scopedEntry(
                identity(
                    revision = "b".repeat(40),
                    expectedBytes = replacement.size.toLong(),
                ),
                bundleId = "1".repeat(64),
                bytes = replacement,
            )
            val target = root / replacementEntry.localRelativePath
            fs.createDirectories(requireNotNull(target.parent))
            fs.write(target.siblingPart()) { write(replacement) }
            val crashing = ArtifactManifestStore(root, fs) { phase ->
                if (phase == crashPhase) throw SimulatedCrash()
            }

            assertFailsWith<SimulatedCrash> {
                crashing.commit(
                    relativePath = replacementEntry.localRelativePath,
                    entry = replacementEntry,
                )
            }

            val restarted = ArtifactManifestStore(root, fs)
            restarted.recover()
            assertEquals(replacement.decodeToString(), fs.read(target) { readUtf8() }, crashPhase.name)
            assertEquals(replacementEntry, restarted.readValidated()?.entries?.single(), crashPhase.name)
            assertFalse(fs.exists(root / ArtifactManifestStore.JOURNAL_FILE_NAME))
        }
    }

    @Test
    fun commitRejectsCallerTargetOutsideTheEntryGenerationWithoutPublishing() {
        val fs = FakeFileSystem()
        val root = "/models/org/unscoped-target".toPath()
        fs.createDirectories(root)
        val target = root / "model.gguf"
        val replacement = "unscoped-target".encodeToByteArray()
        fs.write(target.siblingPart()) { write(replacement) }
        val scopedEntry = entry(
            identity(revision = "b".repeat(40), expectedBytes = replacement.size.toLong()),
            "model",
            replacement.sha256Hex(),
        )

        assertFailsWith<IllegalArgumentException> {
            ArtifactManifestStore(root, fs).commit(
                relativePath = "model.gguf",
                entry = scopedEntry,
            )
        }

        assertNull(ArtifactManifestStore(root, fs).readValidated())
        assertFalse(fs.exists(target))
        assertTrue(fs.exists(target.siblingPart()))
        assertFalse(fs.exists(root / ArtifactManifestStore.JOURNAL_FILE_NAME))
    }

    @Test
    fun unreadableNewPublishedJournalKeepsUnscopedManifestQuarantinedWithoutMutation() {
        val fs = FakeFileSystem()
        val root = "/models/org/unreadable-new-published".toPath()
        fs.createDirectories(root)
        val replacement = "unscoped-new-generation".encodeToByteArray()
        val replacementEntry = entry(
            identity(revision = "b".repeat(40), expectedBytes = replacement.size.toLong()),
            "model",
            replacement.sha256Hex(),
        )
        val target = root / replacementEntry.localRelativePath
        fs.createDirectories(requireNotNull(target.parent))
        fs.write(target.siblingPart()) { write(replacement) }
        val crashing = ArtifactManifestStore(root, fs) { phase ->
            if (phase == ManifestJournalPhase.NEW_PUBLISHED) throw SimulatedCrash()
        }
        assertFailsWith<SimulatedCrash> {
            crashing.commit(
                replacementEntry.localRelativePath,
                replacementEntry,
            )
        }
        rewriteManifestAsUnscoped(fs, root, replacementEntry)
        val journalPath = root / ArtifactManifestStore.JOURNAL_FILE_NAME
        fs.write(journalPath) { writeUtf8("{\"phase\":") }
        val counting = CountingMutationFileSystem(fs, crashIndex = null)
        val restarted = ArtifactManifestStore(root, counting)

        repeat(2) {
            assertEquals(ArtifactManifestRecoveryResult.QUARANTINED, restarted.recover())
            assertNull(restarted.readValidated())
        }

        assertTrue(fs.exists(journalPath))
        assertEquals(0, counting.count)
    }

    @Test
    fun tamperedOldRemovedJournalKeepsUnscopedManifestQuarantinedWithoutMutation() {
        val fs = FakeFileSystem()
        val root = "/models/org/tampered-old-removed".toPath()
        fs.createDirectories(root)
        val old = "old-generation".encodeToByteArray()
        val target = stageAndCommit(
            fs,
            ArtifactManifestStore(root, fs),
            root,
            old,
            revision = "a".repeat(40),
        )
        val replacement = "new-generation".encodeToByteArray()
        val replacementEntry = entry(
            identity(revision = "a".repeat(40), expectedBytes = replacement.size.toLong()),
            "model",
            replacement.sha256Hex(),
        )
        fs.write(target.siblingPart()) { write(replacement) }
        val manifestPrevious = (root / ArtifactManifestStore.MANIFEST_FILE_NAME).siblingPrevious()
        val crashingFs = FaultAfterMutationFileSystem(fs) { kind, path, _ ->
            kind == MutationKind.DELETE && path == manifestPrevious
        }
        assertFailsWith<SimulatedCrash> {
            ArtifactManifestStore(root, crashingFs).commit(
                replacementEntry.localRelativePath,
                replacementEntry,
            )
        }
        rewriteManifestAsUnscoped(fs, root, replacementEntry)
        val journalPath = root / ArtifactManifestStore.JOURNAL_FILE_NAME
        val journal = fs.read(journalPath) { readUtf8() }
        fs.write(journalPath) {
            writeUtf8(journal.replace("\"transactionId\":\"", "\"transactionId\":\"f"))
        }
        val counting = CountingMutationFileSystem(fs, crashIndex = null)
        val restarted = ArtifactManifestStore(root, counting)

        repeat(2) {
            assertEquals(ArtifactManifestRecoveryResult.QUARANTINED, restarted.recover())
            assertNull(restarted.readValidated())
        }

        assertTrue(fs.exists(journalPath))
        assertEquals(0, counting.count)
    }

    @Test
    fun settledUnscopedManifestWithoutJournalIsUnreadable() = withStore { fs, root, store ->
        val bytes = "settled-legacy".encodeToByteArray()
        stageAndCommit(fs, store, root, bytes, revision = "a".repeat(40))
        val manifest = requireNotNull(store.readValidated())
        rewriteManifestAsUnscoped(fs, root, manifest.entries.single())

        assertFalse(fs.exists(root / ArtifactManifestStore.JOURNAL_FILE_NAME))
        assertNull(store.read())
        assertNull(store.readValidated())
    }

    @Test
    fun restartAfterEveryJournalPhaseRestoresOldWhenNewGenerationIsInvalid() {
        ManifestJournalPhase.entries.filterNot { it == ManifestJournalPhase.ROLLING_BACK }.forEach { crashPhase ->
            val fs = FakeFileSystem()
            val root = "/models/org/restore-${crashPhase.name}".toPath()
            fs.createDirectories(root)
            val old = "old-generation".encodeToByteArray()
            val target = stageAndCommit(
                fs,
                ArtifactManifestStore(root, fs),
                root,
                old,
                revision = "a".repeat(40),
            )
            val replacement = ByteArray(old.size) { ('n'.code + crashPhase.ordinal).toByte() }
            fs.write(target.siblingPart()) { write(replacement) }
            val replacementEntry = entry(
                identity(revision = "a".repeat(40), expectedBytes = replacement.size.toLong()),
                "model",
                replacement.sha256Hex(),
            )
            val crashing = ArtifactManifestStore(root, fs) { phase ->
                if (phase == crashPhase) throw SimulatedCrash()
            }
            assertFailsWith<SimulatedCrash> {
                crashing.commit(
                    replacementEntry.localRelativePath,
                    replacementEntry,
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
            { journal -> journal.replace("\"relativePath\":\"", "\"relativePath\":\"other/") },
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
            val old = "known-valid-old-$index".encodeToByteArray()
            val target = stageAndCommit(
                fs,
                ArtifactManifestStore(root, fs),
                root,
                old,
                revision = "a".repeat(40),
            )
            val replacement = ByteArray(old.size) { 'n'.code.toByte() }
            fs.write(target.siblingPart()) { write(replacement) }
            val replacementEntry = entry(
                identity(revision = "a".repeat(40), expectedBytes = replacement.size.toLong()),
                "model",
                replacement.sha256Hex(),
            )
            val crashing = ArtifactManifestStore(root, fs) { phase ->
                if (phase == ManifestJournalPhase.PREPARED) throw SimulatedCrash()
            }
            assertFailsWith<SimulatedCrash> {
                crashing.commit(
                    replacementEntry.localRelativePath,
                    replacementEntry,
                )
            }
            val journalPath = root / ArtifactManifestStore.JOURNAL_FILE_NAME
            val originalJournal = fs.read(journalPath) { readUtf8() }
            fs.write(journalPath) { writeUtf8(tamper(originalJournal)) }

            repeat(3) { ArtifactManifestStore(root, fs).recover() }

            assertTrue(fs.exists(target), "case $index removed the only published target")
            assertEquals(old.decodeToString(), fs.read(target) { readUtf8() }, "case $index")
            assertNotNull(ArtifactManifestStore(root, fs).readValidated(), "case $index")
            assertFalse(fs.exists(journalPath), "case $index")
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
    val replacement = "replacement".encodeToByteArray()
    val old = ByteArray(replacement.size) { 'o'.code.toByte() }
    val artifact = identity(expectedBytes = replacement.size.toLong())
    val bundleId = requireNotNull(artifactBundleId(listOf(artifact)))
    val replacementEntry = scopedEntry(artifact, bundleId, replacement)
    val target = root / replacementEntry.localRelativePath
    installScopedEntry(
        fs = fs,
        root = root,
        store = ArtifactManifestStore(root, fs),
        entry = scopedEntry(artifact, bundleId, old),
        bytes = old,
    )
    fs.write(target.siblingPart()) { write(replacement) }
    val counting = CountingMutationFileSystem(fs, crashIndex = null)
    ArtifactManifestStore(root, counting).commit(
        replacementEntry.localRelativePath,
        replacementEntry,
    )
    return counting.count
}

private fun successfulRollbackMutationCount(): Int {
    val fs = FakeFileSystem()
    val root = "/models/org/rollback-count".toPath()
    prepareInvalidPublishedReplacement(fs, root)
    val counting = CountingMutationFileSystem(fs, crashIndex = null)
    ArtifactManifestStore(root, counting).recover()
    return counting.count
}

private fun prepareInvalidPublishedReplacement(fs: FakeFileSystem, root: Path): Path {
    fs.createDirectories(root)
    val target = stageAndCommit(
        fs,
        ArtifactManifestStore(root, fs),
        root,
        "old-generation".encodeToByteArray(),
        "a".repeat(40),
    )
    val replacement = "new-generation".encodeToByteArray()
    fs.write(target.siblingPart()) { write(replacement) }
    val replacementEntry = entry(
        identity(revision = "a".repeat(40), expectedBytes = replacement.size.toLong()),
        "model",
        replacement.sha256Hex(),
    )
    val crashing = ArtifactManifestStore(root, fs) { phase ->
        if (phase == ManifestJournalPhase.NEW_PUBLISHED) throw SimulatedCrash()
    }
    assertFailsWith<SimulatedCrash> {
        crashing.commit(
            replacementEntry.localRelativePath,
            replacementEntry,
        )
    }
    fs.write(target) { writeUtf8("invalid-new-generation") }
    return target
}

private fun withStore(block: (FakeFileSystem, Path, ArtifactManifestStore) -> Unit) {
    val fs = FakeFileSystem()
    val root = "/models/org/model".toPath()
    fs.createDirectories(root)
    block(fs, root, ArtifactManifestStore(root, fs))
}

private fun rewriteManifestAsUnscoped(
    fs: FileSystem,
    root: Path,
    entry: ArtifactManifestEntry,
) {
    val manifestPath = root / ArtifactManifestStore.MANIFEST_FILE_NAME
    val encoded = fs.read(manifestPath) { readUtf8() }
    val scopedField = "\"localRelativePath\":\"${entry.localRelativePath}\""
    val unscopedField = "\"localRelativePath\":\"${entry.layoutRelativePath}\""
    check(scopedField in encoded)
    fs.write(manifestPath) { writeUtf8(encoded.replace(scopedField, unscopedField)) }
}

private fun stageAndCommit(
    fs: FileSystem,
    store: ArtifactManifestStore,
    root: Path,
    bytes: ByteArray,
    revision: String,
): Path {
    val manifestEntry = entry(
        identity(revision = revision, expectedBytes = bytes.size.toLong()),
        logicalRole = "model",
        digest = bytes.sha256Hex(),
    )
    val target = root / manifestEntry.localRelativePath
    fs.createDirectories(requireNotNull(target.parent))
    fs.write(target.siblingPart()) { write(bytes) }
    store.commit(
        relativePath = manifestEntry.localRelativePath,
        entry = manifestEntry,
    )
    return target
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
): ArtifactManifestEntry {
    val bundleId = requireNotNull(artifactBundleId(listOf(identity)))
    val location = immutableArtifactStorageLocation(identity, bundleId)
    return requireNotNull(
        ArtifactManifestEntry.create(
            logicalRole = logicalRole,
            identity = identity,
            byteCount = byteCount,
            contentSha256 = digest,
            bundleId = bundleId,
            localRelativePath = location.localRelativePath,
            layoutRelativePath = location.layoutRelativePath,
        ),
    )
}

private fun scopedEntry(
    identity: DownloadArtifactIdentity,
    bundleId: String,
    bytes: ByteArray,
): ArtifactManifestEntry {
    val location = immutableArtifactStorageLocation(identity, bundleId)
    return requireNotNull(
        ArtifactManifestEntry.create(
            logicalRole = "model",
            identity = identity,
            byteCount = identity.expectedBytes,
            contentSha256 = bytes.sha256Hex(),
            bundleId = bundleId,
            localRelativePath = location.localRelativePath,
            layoutRelativePath = location.layoutRelativePath,
        ),
    )
}

private fun installScopedEntry(
    fs: FileSystem,
    root: Path,
    store: ArtifactManifestStore,
    entry: ArtifactManifestEntry,
    bytes: ByteArray,
) {
    val target = root / entry.localRelativePath
    fs.createDirectories(requireNotNull(target.parent))
    fs.write(target.siblingPart()) { write(bytes) }
    store.commit(entry.localRelativePath, entry)
}

private fun Path.siblingPart(): Path = "$this.part".toPath()
private fun Path.siblingPrevious(): Path = "$this.previous".toPath()

private fun ByteArray.sha256Hex(): String = okio.ByteString.of(*this).sha256().hex()
