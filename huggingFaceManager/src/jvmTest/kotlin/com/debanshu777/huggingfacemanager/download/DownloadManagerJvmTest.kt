package com.debanshu777.huggingfacemanager.download

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okio.Path.Companion.toPath as toOkioPath
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DownloadManagerJvmTest {
    @Test
    fun storageInspectionUsesOnlyCanonicalDestinationAndManifestCheckpoint() = withTemporaryRoot { root ->
        val publishedBytes = "published".encodeToByteArray()
        val published = scopedMetadata("weights/model.gguf", "a".repeat(40), publishedBytes)
        val paths = TestStoragePathProvider(root)
        withServer { exchange ->
            exchange.respond(200, publishedBytes.size.toLong(), publishedBytes)
        }.use { server ->
            val manager = DownloadManager(paths, server.baseUrl)
            runBlocking { manager.download("org/model", published.artifact.relativePath, published).toList() }

            val installed = requireNotNull(runBlocking { manager.inspectStorage(listOf(published)) }).single()
            assertTrue(installed.exactPublished)
            assertEquals(publishedBytes.size.toLong(), installed.targetBytes)
            assertEquals(null, installed.stagedBytes)

            val pendingBytes = "pending".encodeToByteArray()
            val pending = scopedMetadata("weights/model.gguf", "b".repeat(40), pendingBytes)
            modelFile(root, "org/model", pending.artifact.relativePath).apply {
                parentFile.mkdirs()
                writeBytes(pendingBytes)
            }
            modelFile(root, "org/model", pending.destinationRelativePath + ".part").apply {
                parentFile.mkdirs()
                writeBytes(pendingBytes.copyOf(2))
            }

            val inspected = requireNotNull(runBlocking { manager.inspectStorage(listOf(pending)) }).single()
            assertFalse(inspected.exactPublished)
            assertEquals(null, inspected.targetBytes)
            assertEquals(2L, inspected.stagedBytes)
        }
    }

    @Test
    fun cleanupRetryAfterManifestPruneDeletesTheRemainingUnreferencedBytes() = withTemporaryRoot { root ->
        val bytes = "cleanup-retry".encodeToByteArray()
        val metadata = scopedMetadata("model.gguf", "a".repeat(40), bytes)
        val paths = TestStoragePathProvider(root)
        withServer { exchange -> exchange.respond(200, bytes.size.toLong(), bytes) }.use { server ->
            val manager = DownloadManager(paths, server.baseUrl)
            runBlocking { manager.download("org/model", metadata.artifact.relativePath, metadata).toList() }
            val entry = requireNotNull(runBlocking { manager.validatedArtifacts("org/model") }).entries.single()
            val modelRoot = modelFile(root, "org/model", "")
            val store = ArtifactManifestStore(modelRoot.absolutePath.toOkioPath())
            try {
                assertTrue(store.pruneValidated(listOf(entry)))
            } finally {
                store.close()
            }

            assertTrue(runBlocking { deleteValidatedArtifactEntries(paths, listOf(entry)) })
            assertFalse(modelFile(root, "org/model", metadata.destinationRelativePath).exists())
        }
    }

    @Test
    fun currentBundleLifetimeReturnsValidatedOwnerManifestUnderTheRootLease() = withTemporaryRoot { root ->
        val storage = TestStoragePathProvider(root)
        val installed = installArtifact(root)
        val modelRoot = modelFile(root, installed.artifact.repositoryId, "")
        assertTrue(runBlocking { DownloadManager(storage).publishBundle(installed.artifact.repositoryId, listOf(installed)) })

        val current = runBlocking {
            artifactRootLifetime(storage).withCurrentBundle(
                ownerModelId = installed.artifact.repositoryId,
                expectedRepositoryRoots = mapOf(installed.artifact.repositoryId to modelRoot.absolutePath),
            ) { it }
        }

        assertEquals(installed.artifact, assertNotNull(current).entries.single().identity)
    }

    @Test
    fun currentBundleLifetimeDiscoversAndLocksExternalCandidateRoot() = withTemporaryRoot { root ->
        val storage = TestStoragePathProvider(root)
        val ownerBytes = "owner-component".encodeToByteArray()
        val externalBytes = "external-component".encodeToByteArray()
        val ownerIdentity = requireNotNull(
            DownloadArtifactIdentity.create(
                repositoryId = "org/model",
                immutableRevision = "a".repeat(40),
                relativePath = "model.safetensors",
                remoteObjectId = "sha256:${ownerBytes.sha256Hex()}",
                expectedBytes = ownerBytes.size.toLong(),
            ),
        )
        val externalIdentity = requireNotNull(
            DownloadArtifactIdentity.create(
                repositoryId = "external/vae",
                immutableRevision = "b".repeat(40),
                relativePath = "vae.safetensors",
                remoteObjectId = "sha256:${externalBytes.sha256Hex()}",
                expectedBytes = externalBytes.size.toLong(),
            ),
        )
        val bundleId = requireNotNull(artifactBundleId(listOf(ownerIdentity, externalIdentity)))
        val owner = metadataForBundle(ownerIdentity, "model", bundleId)
        val external = metadataForBundle(externalIdentity, "vae", bundleId)
        installArtifact(root, owner, ownerBytes)
        installArtifact(root, external, externalBytes)
        val manager = DownloadManager(storage)
        assertTrue(runBlocking { manager.publishBundle(ownerIdentity.repositoryId, listOf(owner, external)) })
        val ownerRoot = modelFile(root, ownerIdentity.repositoryId, "")
        val externalRoot = modelFile(root, externalIdentity.repositoryId, "")

        runBlocking {
            coroutineScope {
                val mutationEntered = CompletableDeferred<Unit>()
                lateinit var mutation: Deferred<Unit>
                val current = artifactRootLifetime(storage).withCurrentBundle(
                    ownerModelId = ownerIdentity.repositoryId,
                    expectedRepositoryRoots = mapOf(ownerIdentity.repositoryId to ownerRoot.absolutePath),
                ) { manifest ->
                    mutation = async(start = CoroutineStart.UNDISPATCHED) {
                        ArtifactRootLockCoordinator.withRoots(listOf(externalRoot.absolutePath)) {
                            mutationEntered.complete(Unit)
                        }
                    }
                    assertFalse(mutationEntered.isCompleted)
                    manifest
                }

                assertEquals(2, assertNotNull(current).entries.size)
                mutation.await()
                assertTrue(mutationEntered.isCompleted)
            }
        }
    }

    @Test
    fun currentBundleLifetimeDiscoversStagedExternalRootBeforeRecovery() = withTemporaryRoot { root ->
        val storage = TestStoragePathProvider(root)
        val old = installArtifact(root)
        val manager = DownloadManager(storage)
        assertTrue(runBlocking { manager.publishBundle(old.artifact.repositoryId, listOf(old)) })
        val ownerRoot = modelFile(root, old.artifact.repositoryId, "")
        val newOwnerBytes = "new-owner-component".encodeToByteArray()
        val externalBytes = "staged-external-component".encodeToByteArray()
        val newOwnerIdentity = requireNotNull(
            DownloadArtifactIdentity.create(
                repositoryId = old.artifact.repositoryId,
                immutableRevision = "c".repeat(40),
                relativePath = "model.safetensors",
                remoteObjectId = "sha256:${newOwnerBytes.sha256Hex()}",
                expectedBytes = newOwnerBytes.size.toLong(),
            ),
        )
        val externalIdentity = requireNotNull(
            DownloadArtifactIdentity.create(
                repositoryId = "external/staged-vae",
                immutableRevision = "d".repeat(40),
                relativePath = "vae.safetensors",
                remoteObjectId = "sha256:${externalBytes.sha256Hex()}",
                expectedBytes = externalBytes.size.toLong(),
            ),
        )
        val bundleId = requireNotNull(artifactBundleId(listOf(newOwnerIdentity, externalIdentity)))
        val newOwner = metadataForBundle(newOwnerIdentity, "model", bundleId)
        val external = metadataForBundle(externalIdentity, "vae", bundleId)
        installArtifact(root, newOwner, newOwnerBytes)
        installArtifact(root, external, externalBytes)
        val stagedEntries = listOf(
            manifestEntry(newOwner, newOwnerBytes),
            manifestEntry(external, externalBytes),
        )
        val expected = requireNotNull(ArtifactManifest.create(stagedEntries))
        val crashingStore = ArtifactBundleManifestStore(
            ownerRoot.absolutePath.toOkioPath(),
            phaseObserver = { phase ->
                if (phase == ManifestJournalPhase.PREPARED) throw SimulatedRootLifetimeCrash()
            },
            artifactValidator = { true },
        )
        assertFailsWith<SimulatedRootLifetimeCrash> { crashingStore.publish(stagedEntries) }
        crashingStore.close()
        val journal = File(ownerRoot, ArtifactBundleManifestStore.JOURNAL_FILE_NAME)
        val part = File(ownerRoot, ArtifactBundleManifestStore.MANIFEST_FILE_NAME + ".part")
        assertTrue(journal.exists())
        assertTrue(part.exists())

        val canonicalCurrent = runBlocking { manager.validatedBundle(old.artifact.repositoryId) }
        assertEquals(expected, canonicalCurrent)
        val current = runBlocking {
            artifactRootLifetime(storage).withCurrentBundle(
                ownerModelId = old.artifact.repositoryId,
                expectedRepositoryRoots = mapOf(old.artifact.repositoryId to ownerRoot.absolutePath),
            ) { it }
        }

        assertEquals(expected, current)
        assertFalse(journal.exists())
        assertFalse(part.exists())
    }

    @Test
    fun sharedRootLifetimeBlocksDownloadCommitUntilValidatedConsumerExits() = withTemporaryRoot { root ->
        val storage = TestStoragePathProvider(root)
        val oldMetadata = installArtifact(root)
        val oldTarget = modelFile(root, oldMetadata.artifact.repositoryId, oldMetadata.destinationRelativePath)
        val modelRoot = modelFile(root, oldMetadata.artifact.repositoryId, "")
        val manifestFile = File(modelRoot, ArtifactManifestStore.MANIFEST_FILE_NAME)
        val oldTargetBytes = oldTarget.readBytes()
        val oldManifestBytes = manifestFile.readBytes()
        val newBytes = "replacement-generation".encodeToByteArray()
        val newMetadata = scopedMetadata("model.gguf", "b".repeat(40), newBytes)
        val newTarget = modelFile(root, newMetadata.artifact.repositoryId, newMetadata.destinationRelativePath)
        val requests = AtomicInteger()

        withServer { exchange ->
            requests.incrementAndGet()
            exchange.respond(status = 200, declaredLength = newBytes.size.toLong(), body = newBytes)
        }.use { server ->
            val manager = DownloadManager(storage, server.baseUrl)
            runBlocking {
                coroutineScope {
                    lateinit var replacement: Deferred<List<DownloadProgressDTO>>
                    ArtifactRootLockCoordinator.withRoots(listOf(modelRoot.absolutePath)) {
                        replacement = async(start = CoroutineStart.UNDISPATCHED) {
                            manager.download(
                                newMetadata.artifact.repositoryId,
                                newMetadata.artifact.relativePath,
                                newMetadata,
                            ).toList()
                        }

                        assertFalse(replacement.isCompleted)
                        assertEquals(0, requests.get())
                        assertContentEquals(oldTargetBytes, oldTarget.readBytes())
                        assertContentEquals(oldManifestBytes, manifestFile.readBytes())
                        assertFalse(newTarget.exists())
                    }

                    replacement.await()
                }
            }
        }

        assertEquals(1, requests.get())
        assertContentEquals(oldTargetBytes, oldTarget.readBytes())
        assertContentEquals(newBytes, newTarget.readBytes())
        assertNotEquals(oldManifestBytes.toList(), manifestFile.readBytes().toList())
        val entries = ArtifactManifestStore(modelRoot.absolutePath.toOkioPath()).readValidated()?.entries.orEmpty()
        assertEquals(1, entries.count { it.identity == newMetadata.artifact })
    }

    @Test
    fun sharedRootLifetimeBlocksValidatedReadJournalRecoveryWithoutMutation() = withTemporaryRoot { root ->
        val storage = TestStoragePathProvider(root)
        val oldMetadata = installArtifact(root)
        val modelRoot = modelFile(root, oldMetadata.artifact.repositoryId, "")
        val oldTarget = modelFile(root, oldMetadata.artifact.repositoryId, oldMetadata.destinationRelativePath)
        val manifestFile = File(modelRoot, ArtifactManifestStore.MANIFEST_FILE_NAME)
        val newBytes = "recoverable-generation".encodeToByteArray()
        val newMetadata = scopedMetadata("model.gguf", "c".repeat(40), newBytes)
        val newTargetPart = modelFile(
            root,
            newMetadata.artifact.repositoryId,
            newMetadata.destinationRelativePath + ".part",
        ).apply {
            parentFile.mkdirs()
            writeBytes(newBytes)
        }
        val crashingStore = ArtifactManifestStore(
            modelRoot.absolutePath.toOkioPath(),
            phaseObserver = { phase ->
                if (phase == ManifestJournalPhase.PREPARED) throw SimulatedRootLifetimeCrash()
            },
        )
        assertFailsWith<SimulatedRootLifetimeCrash> {
            crashingStore.commit(
                newMetadata.destinationRelativePath,
                requireNotNull(
                    ArtifactManifestEntry.create(
                        logicalRole = newMetadata.logicalRole,
                        identity = newMetadata.artifact,
                        byteCount = newBytes.size.toLong(),
                        contentSha256 = newBytes.sha256Hex(),
                        bundleId = newMetadata.bundleId,
                        localRelativePath = newMetadata.destinationRelativePath,
                        layoutRelativePath = newMetadata.layoutRelativePath,
                    ),
                ),
            )
        }
        crashingStore.close()
        val journalFile = File(modelRoot, ArtifactManifestStore.JOURNAL_FILE_NAME)
        val manifestPartFile = File(modelRoot, ArtifactManifestStore.MANIFEST_FILE_NAME + ".part")
        val oldTargetBytes = oldTarget.readBytes()
        val oldManifestBytes = manifestFile.readBytes()
        val journalBytes = journalFile.readBytes()
        val manifestPartBytes = manifestPartFile.readBytes()
        val manager = DownloadManager(storage, "https://huggingface.co")

        runBlocking {
            coroutineScope {
                lateinit var validatedRead: Deferred<ArtifactManifest?>
                ArtifactRootLockCoordinator.withRoots(listOf(modelRoot.absolutePath)) {
                    validatedRead = async(start = CoroutineStart.UNDISPATCHED) {
                        manager.validatedArtifacts(oldMetadata.artifact.repositoryId)
                    }

                    assertFalse(validatedRead.isCompleted)
                    assertContentEquals(oldTargetBytes, oldTarget.readBytes())
                    assertContentEquals(oldManifestBytes, manifestFile.readBytes())
                    assertContentEquals(journalBytes, journalFile.readBytes())
                    assertContentEquals(manifestPartBytes, manifestPartFile.readBytes())
                    assertContentEquals(newBytes, newTargetPart.readBytes())
                }

                val recovered = assertNotNull(validatedRead.await())
                assertTrue(recovered.entries.any { it.identity == newMetadata.artifact })
            }
        }

        assertFalse(journalFile.exists())
        assertFalse(manifestPartFile.exists())
    }

    @Test
    fun unscopedDestinationIsRejectedAtTypedBoundaryBeforeNetworkOrStorageMutation() =
        withTemporaryRoot { root ->
            val requests = AtomicInteger()
            val bytes = "legacy-write-must-not-run".encodeToByteArray()
            val scoped = scopedMetadata("model.gguf", "a".repeat(40), bytes)

            withServer { exchange ->
                requests.incrementAndGet()
                exchange.respond(status = 200, declaredLength = bytes.size.toLong(), body = bytes)
            }.use { server ->
                DownloadManager(TestStoragePathProvider(root), server.baseUrl)
                assertFailsWith<IllegalArgumentException> {
                    scoped.copy(destinationRelativePath = scoped.layoutRelativePath)
                }
            }

            assertEquals(0, requests.get())
            assertFalse(modelFile(root, "org/model", scoped.layoutRelativePath).exists())
            assertFalse(modelFile(root, "org/model", scoped.layoutRelativePath + ".part").exists())
        }

    @Test
    fun unscopedDestinationCannotReachPublicMutationSeams() =
        withTemporaryRoot { root ->
            val storageResolutions = AtomicInteger()
            val bytes = "legacy-mutation-must-not-run".encodeToByteArray()
            val scoped = scopedMetadata("model.gguf", "a".repeat(40), bytes)
            DownloadManager(TestStoragePathProvider(root, storageResolutions))

            assertFailsWith<IllegalArgumentException> {
                scoped.copy(destinationRelativePath = scoped.layoutRelativePath)
            }
            assertEquals(0, storageResolutions.get())
        }

    @Test
    fun settledUnscopedManifestIsNotPublishedOrReadable() = withTemporaryRoot { root ->
        val bytes = "unscoped-installed-model".encodeToByteArray()
        val scoped = scopedMetadata("model.gguf", "a".repeat(40), bytes)
        val modelRoot = modelFile(root, "org/model", "").apply { mkdirs() }
        File(modelRoot, scoped.destinationRelativePath + ".part").apply {
            parentFile.mkdirs()
            writeBytes(bytes)
        }
        ArtifactManifestStore(modelRoot.absolutePath.toOkioPath()).commit(
            relativePath = scoped.destinationRelativePath,
            entry = requireNotNull(
                ArtifactManifestEntry.create(
                    logicalRole = scoped.logicalRole,
                    identity = scoped.artifact,
                    byteCount = bytes.size.toLong(),
                    contentSha256 = bytes.sha256Hex(),
                    bundleId = scoped.bundleId,
                    localRelativePath = scoped.destinationRelativePath,
                    layoutRelativePath = scoped.layoutRelativePath,
                ),
            ),
        )
        val manifestFile = File(modelRoot, ArtifactManifestStore.MANIFEST_FILE_NAME)
        manifestFile.writeText(
            manifestFile.readText().replace(
                "\"localRelativePath\":\"${scoped.destinationRelativePath}\"",
                "\"localRelativePath\":\"${scoped.layoutRelativePath}\"",
            ),
        )
        val manager = DownloadManager(TestStoragePathProvider(root))

        assertFalse(runBlocking { manager.isPublished(scoped) })
        assertEquals(null, runBlocking { manager.validatedArtifacts("org/model") })
    }

    @Test
    fun deletingOneImmutableRevisionPrunesItsManifestAndKeepsTheOtherPublishedAfterRestart() =
        withTemporaryRoot { root ->
            val firstBytes = "revision-one".encodeToByteArray()
            val secondBytes = "revision-two".encodeToByteArray()
            val first = scopedMetadata("shared/model.gguf", "a".repeat(40), firstBytes)
            val second = scopedMetadata("shared/model.gguf", "b".repeat(40), secondBytes)
            val paths = TestStoragePathProvider(root)

            withServer { exchange ->
                val body = if (first.artifact.immutableRevision in exchange.requestURI.path) firstBytes else secondBytes
                exchange.respond(status = 200, declaredLength = body.size.toLong(), body = body)
            }.use { server ->
                val manager = DownloadManager(paths, server.baseUrl)
                runBlocking {
                    manager.download("org/model", first.artifact.relativePath, first).toList()
                    manager.download("org/model", second.artifact.relativePath, second).toList()
                    val firstEntry = requireNotNull(manager.validatedArtifacts("org/model"))
                        .entries.single { it.identity == first.artifact }

                    assertTrue(deleteValidatedArtifactEntries(paths, listOf(firstEntry)))
                }
            }

            val reopened = DownloadManager(paths, "https://huggingface.co")
            runBlocking {
                assertFalse(reopened.isPublished(first))
                assertTrue(reopened.isPublished(second))
                assertEquals(listOf(second.artifact), reopened.validatedArtifacts("org/model")?.entries?.map { it.identity })
            }
            assertFalse(modelFile(root, "org/model", first.destinationRelativePath).exists())
            assertContentEquals(
                secondBytes,
                modelFile(root, "org/model", second.destinationRelativePath).readBytes(),
            )
        }

    @Test
    fun concurrentImmutableRevisionsOfSameRepositoryPathSurviveRestart() = withTemporaryRoot { root ->
        val revisionOne = "a".repeat(40)
        val revisionTwo = "b".repeat(40)
        val bytesOne = "revision-one".encodeToByteArray()
        val bytesTwo = "revision-two".encodeToByteArray()
        val metadataOne = scopedMetadata("shared/model.gguf", revisionOne, bytesOne)
        val metadataTwo = scopedMetadata("shared/model.gguf", revisionTwo, bytesTwo)

        withServer { exchange ->
            val body = if (revisionOne in exchange.requestURI.path) bytesOne else bytesTwo
            exchange.respond(status = 200, declaredLength = body.size.toLong(), body = body)
        }.use { server ->
            val manager = DownloadManager(TestStoragePathProvider(root), server.baseUrl)
            runBlocking {
                val first = async {
                    manager.download("org/model", "shared/model.gguf", metadataOne).toList().last()
                }
                val second = async {
                    manager.download("org/model", "shared/model.gguf", metadataTwo).toList().last()
                }
                val firstPath = assertNotNull(first.await().localPath)
                val secondPath = assertNotNull(second.await().localPath)
                assertNotEquals(firstPath, secondPath)
                assertContentEquals(bytesOne, File(firstPath).readBytes())
                assertContentEquals(bytesTwo, File(secondPath).readBytes())
                assertTrue(manager.isPublished(metadataOne))
                assertTrue(manager.isPublished(metadataTwo))
            }
        }

        val reopened = DownloadManager(TestStoragePathProvider(root), "https://huggingface.co")
        runBlocking {
            assertTrue(reopened.isPublished(metadataOne))
            assertTrue(reopened.isPublished(metadataTwo))
            assertEquals(2, reopened.validatedArtifacts("org/model")?.entries?.size)
        }
    }

    @Test
    fun validPartialResponseAppendsFromPersistedCheckpoint() = withTemporaryRoot { root ->
        val expected = "0123456789".encodeToByteArray()
        val metadata = metadata("model.gguf", expected.size.toLong(), expected.sha256Hex())
        val finalFile = modelFile(root, "org/model", metadata.destinationRelativePath)
        finalFile.parentFile.mkdirs()
        File(finalFile.path + ".part").writeBytes(expected.copyOfRange(0, 4))
        withServer { exchange ->
            assertEquals("bytes=4-", exchange.requestHeaders.getFirst("Range"))
            assertEquals("etag-1", exchange.requestHeaders.getFirst("If-Range"))
            exchange.responseHeaders.add("Content-Range", "bytes 4-9/10")
            exchange.responseHeaders.add("ETag", "etag-1")
            exchange.respond(status = 206, declaredLength = 6L, body = expected.copyOfRange(4, 10))
        }.use { server ->
            val manager = DownloadManager(TestStoragePathProvider(root), server.baseUrl)
            runBlocking {
                manager.download(
                    "org/model",
                    "model.gguf",
                    metadata,
                    DownloadResumeMetadata(bytesReceived = 4L, entityTag = "etag-1", lastModified = null),
                ).toList()
            }
        }

        assertContentEquals(expected, finalFile.readBytes())
    }

    @Test
    fun fullResponseToRangeRequestSafelyRestartsStaging() = withTemporaryRoot { root ->
        val expected = "replacement".encodeToByteArray()
        val metadata = metadata("model.gguf", expected.size.toLong(), expected.sha256Hex())
        val finalFile = modelFile(root, "org/model", metadata.destinationRelativePath)
        finalFile.parentFile.mkdirs()
        File(finalFile.path + ".part").writeBytes("stale".encodeToByteArray())
        withServer { exchange ->
            assertEquals("bytes=5-", exchange.requestHeaders.getFirst("Range"))
            exchange.respond(status = 200, declaredLength = expected.size.toLong(), body = expected)
        }.use { server ->
            val manager = DownloadManager(TestStoragePathProvider(root), server.baseUrl)
            runBlocking {
                manager.download(
                    "org/model",
                    "model.gguf",
                    metadata,
                    DownloadResumeMetadata(bytesReceived = 5L, entityTag = "etag-old", lastModified = null),
                ).toList()
            }
        }

        assertContentEquals(expected, finalFile.readBytes())
    }

    @Test
    fun nonSuccessResponseDoesNotReplaceExistingModel() = withTemporaryRoot { root ->
        val original = byteArrayOf(1, 2, 3)
        val metadata = metadata("weights/model.gguf", 9L)
        val finalFile = modelFile(root, "org/model", metadata.destinationRelativePath)
        finalFile.parentFile.mkdirs()
        finalFile.writeBytes(original)

        withServer { exchange ->
            exchange.respond(status = 404, declaredLength = 9L, body = "not found".encodeToByteArray())
        }.use { server ->
            val manager = DownloadManager(TestStoragePathProvider(root), server.baseUrl)

            assertFailsWith<DownloadHttpException> {
                runBlocking {
                    manager.download("org/model", "weights/model.gguf", metadata).toList()
                }
            }
        }

        assertContentEquals(original, finalFile.readBytes())
        assertFalse(File(finalFile.path + ".part").exists())
        assertFalse(File(modelFile(root, "org/model", ""), ArtifactManifestStore.MANIFEST_FILE_NAME).exists())
    }

    @Test
    fun truncatedResponseRemovesTemporaryFileAndPreservesExistingModel() = withTemporaryRoot { root ->
        val original = byteArrayOf(4, 5, 6)
        val metadata = metadata("model.gguf", 10L)
        val finalFile = modelFile(root, "org/model", metadata.destinationRelativePath)
        finalFile.parentFile.mkdirs()
        finalFile.writeBytes(original)

        withServer { exchange ->
            exchange.respond(status = 200, declaredLength = 10L, body = byteArrayOf(9, 8, 7))
        }.use { server ->
            val manager = DownloadManager(TestStoragePathProvider(root), server.baseUrl)

            assertFails {
                runBlocking {
                    manager.download("org/model", "model.gguf", metadata).toList()
                }
            }
        }

        assertContentEquals(original, finalFile.readBytes())
        assertFalse(File(finalFile.path + ".part").exists())
        assertFalse(File(modelFile(root, "org/model", ""), ArtifactManifestStore.MANIFEST_FILE_NAME).exists())
    }

    @Test
    fun successfulResponseCommitsExactBytesAndPublishesFinalPath() = withTemporaryRoot { root ->
        val expected = ByteArray(32_768) { index -> (index % 251).toByte() }
        val metadata = metadata("weights/model.gguf", expected.size.toLong(), expected.sha256Hex())

        withServer { exchange ->
            exchange.respond(status = 200, declaredLength = expected.size.toLong(), body = expected)
        }.use { server ->
            val manager = DownloadManager(TestStoragePathProvider(root), server.baseUrl)
            val events = runBlocking {
                manager.download(
                    "org/model",
                    "weights/model.gguf",
                    metadata,
                ).toList()
            }
            val finalFile = modelFile(root, "org/model", metadata.destinationRelativePath)

            assertTrue(finalFile.isFile)
            assertContentEquals(expected, finalFile.readBytes())
            assertFalse(File(finalFile.path + ".part").exists())
            assertEquals(finalFile.canonicalPath, events.last().localPath)
            assertEquals(100f, events.last().percentage)
            assertEquals(expected.sha256Hex(), events.last().contentSha256)
            val manifest = ArtifactManifestStore(
                modelFile(root, "org/model", "").absolutePath.toOkioPath(),
            ).read()
            assertEquals(expected.sha256Hex(), manifest?.entries?.single()?.contentSha256)
            assertEquals(
                manifest,
                runBlocking { manager.validatedArtifacts("org/model") },
            )
        }
    }

    @Test
    fun requestUsesImmutableRevisionAndEncodedRelativePath() = withTemporaryRoot { root ->
        val requestedPath = AtomicReference<String>()
        val expected = byteArrayOf(7)
        withServer { exchange ->
            requestedPath.set(exchange.requestURI.rawPath)
            exchange.respond(status = 200, declaredLength = 1L, body = expected)
        }.use { server ->
            val manager = DownloadManager(TestStoragePathProvider(root), server.baseUrl)
            runBlocking {
                manager.download(
                    "org/model",
                    "weights/model file.gguf",
                    metadata("weights/model file.gguf", 1L, expected.sha256Hex()),
                ).toList()
            }
        }

        assertEquals(
            "/org/model/resolve/${"a".repeat(40)}/weights/model%20file.gguf",
            requestedPath.get(),
        )
    }

    @Test
    fun unicodeArtifactDownloadsWithStandardUtf8AndIsJavaVisibleAfterManifestValidation() =
        withTemporaryRoot { root ->
            val path = "weights/模型-😀.gguf"
            val expected = "unicode-download".encodeToByteArray()
            val metadata = metadata(path, expected.size.toLong(), expected.sha256Hex())
            withServer { exchange ->
                exchange.respond(status = 200, declaredLength = expected.size.toLong(), body = expected)
            }.use { server ->
                val manager = DownloadManager(TestStoragePathProvider(root), server.baseUrl)
                runBlocking {
                    manager.download(
                        "org/model",
                        path,
                        metadata,
                    ).toList()
                }

                val finalFile = modelFile(root, "org/model", metadata.destinationRelativePath)
                assertTrue(finalFile.isFile)
                assertContentEquals(expected, finalFile.readBytes())
                assertEquals(
                    path,
                    runBlocking {
                        manager.validatedArtifacts("org/model")?.entries?.single()?.identity?.relativePath
                    },
                )
            }
        }

    @Test
    fun malformedSurrogateIdentityIsRejected() {
        assertEquals(
            null,
            DownloadArtifactIdentity.create(
                repositoryId = "org/model",
                immutableRevision = "a".repeat(40),
                relativePath = "bad-\uD800.gguf",
                remoteObjectId = null,
                expectedBytes = 1L,
            ),
        )
    }

    @Test
    fun mismatchedLegacyArgumentsFailBeforeNetworkAccess() = withTemporaryRoot { root ->
        val requests = AtomicInteger()
        val storageResolutions = AtomicInteger()
        withServer { exchange ->
            requests.incrementAndGet()
            exchange.respond(status = 200, declaredLength = 1L, body = byteArrayOf(1))
        }.use { server ->
            val manager = DownloadManager(TestStoragePathProvider(root, storageResolutions), server.baseUrl)

            assertFailsWith<IllegalArgumentException> {
                runBlocking {
                    manager.download("other/model", "model.gguf", metadata("model.gguf", 1L)).toList()
                }
            }
        }

        assertEquals(0, requests.get())
        assertEquals(0, storageResolutions.get())
    }

    @Test
    fun concurrentFilesInOneModelRootPublishOneCompleteManifest() = withTemporaryRoot { root ->
        val first = "first".encodeToByteArray()
        val second = "second".encodeToByteArray()
        withServer { exchange ->
            val body = if (exchange.requestURI.path.endsWith("first.gguf")) first else second
            exchange.respond(status = 200, declaredLength = body.size.toLong(), body = body)
        }.use { server ->
            val manager = DownloadManager(TestStoragePathProvider(root), server.baseUrl)
            runBlocking {
                val one = async {
                    manager.download(
                        "org/model",
                        "first.gguf",
                        metadata("first.gguf", first.size.toLong(), first.sha256Hex(), role = "part-1"),
                    ).toList()
                }
                val two = async {
                    manager.download(
                        "org/model",
                        "second.gguf",
                        metadata("second.gguf", second.size.toLong(), second.sha256Hex(), role = "part-2"),
                    ).toList()
                }
                one.await()
                two.await()
            }
        }

        val modelRoot = File(root, "models/org/model")
        val manifest = ArtifactManifestStore(modelRoot.absolutePath.toOkioPath()).read()
        assertEquals(setOf("part-1", "part-2"), manifest?.entries?.map { it.logicalRole }?.toSet())
    }

    @Test
    fun cancelledValidatedBundleRootLockWaitNeverReturnsAbsence() = withTemporaryRoot { root ->
        val storage = TestStoragePathProvider(root)
        val manager = DownloadManager(storage, "https://huggingface.co")
        val metadata = installArtifact(root)
        runBlocking {
            assertTrue(manager.publishBundle("org/model", listOf(metadata)))
            assertRootLockCancellationDoesNotReturn(
                root = modelFile(root, "org/model", "").absolutePath,
                operation = { manager.validatedBundle("org/model") },
            )
        }
    }

    @Test
    fun cancelledPublishBundleRootLockWaitNeverReturnsFailureValue() = withTemporaryRoot { root ->
        val storage = TestStoragePathProvider(root)
        val manager = DownloadManager(storage, "https://huggingface.co")
        val metadata = installArtifact(root)
        runBlocking {
            assertRootLockCancellationDoesNotReturn(
                root = modelFile(root, "org/model", "").absolutePath,
                operation = { manager.publishBundle("org/model", listOf(metadata)) },
            )
        }
    }

    @Test
    fun unsafePathFailsBeforeNetworkAccess() = withTemporaryRoot { root ->
        val requests = AtomicInteger()
        withServer { exchange ->
            requests.incrementAndGet()
            exchange.respond(status = 200, declaredLength = 1L, body = byteArrayOf(1))
        }.use { server ->
            val manager = DownloadManager(TestStoragePathProvider(root), server.baseUrl)

            assertFailsWith<IllegalArgumentException> {
                runBlocking {
                    manager.download("org/model", "../escape.gguf", metadata("model.gguf", 1L)).toList()
                }
            }
        }

        assertEquals(0, requests.get())
        assertFalse(File(root.parentFile, "escape.gguf").exists())
    }

    private fun metadata(
        path: String,
        expectedBytes: Long,
        digest: String? = null,
        role: String = "model",
    ) = DownloadMetadataDTO(
        artifact = requireNotNull(
            DownloadArtifactIdentity.create(
                repositoryId = "org/model",
                immutableRevision = "a".repeat(40),
                relativePath = path,
                remoteObjectId = digest?.let { "sha256:$it" },
                expectedBytes = expectedBytes,
            ),
        ),
        logicalRole = role,
        sizeBytes = expectedBytes,
        author = null,
        libraryName = null,
        pipelineTag = null,
    )

    private fun scopedMetadata(
        path: String,
        revision: String,
        bytes: ByteArray,
    ): DownloadMetadataDTO {
        val identity = requireNotNull(
            DownloadArtifactIdentity.create(
                repositoryId = "org/model",
                immutableRevision = revision,
                relativePath = path,
                remoteObjectId = "sha256:${bytes.sha256Hex()}",
                expectedBytes = bytes.size.toLong(),
            ),
        )
        return DownloadMetadataDTO(
            artifact = identity,
            logicalRole = "model",
            sizeBytes = bytes.size.toLong(),
            author = null,
            libraryName = null,
            pipelineTag = null,
        )
    }

    private fun installArtifact(root: File): DownloadMetadataDTO {
        val bytes = "installed-model".encodeToByteArray()
        val metadata = metadata("model.gguf", bytes.size.toLong(), bytes.sha256Hex())
        installArtifact(root, metadata, bytes)
        return metadata
    }

    private fun installArtifact(root: File, metadata: DownloadMetadataDTO, bytes: ByteArray) {
        val exactModelRoot = modelFile(root, metadata.artifact.repositoryId, "").apply { mkdirs() }
        File(exactModelRoot, metadata.destinationRelativePath + ".part").apply {
            parentFile.mkdirs()
            writeBytes(bytes)
        }
        ArtifactManifestStore(exactModelRoot.absolutePath.toOkioPath()).commit(
            relativePath = metadata.destinationRelativePath,
            entry = requireNotNull(
                ArtifactManifestEntry.create(
                    logicalRole = metadata.logicalRole,
                    identity = metadata.artifact,
                    byteCount = bytes.size.toLong(),
                    contentSha256 = bytes.sha256Hex(),
                    bundleId = metadata.bundleId,
                    localRelativePath = metadata.destinationRelativePath,
                    layoutRelativePath = metadata.layoutRelativePath,
                ),
            ),
        )
    }

    private fun metadataForBundle(
        identity: DownloadArtifactIdentity,
        role: String,
        bundleId: String,
    ) = DownloadMetadataDTO(
        artifact = identity,
        logicalRole = role,
        sizeBytes = identity.expectedBytes,
        author = null,
        libraryName = null,
        pipelineTag = null,
        bundleId = bundleId,
    )

    private fun manifestEntry(metadata: DownloadMetadataDTO, bytes: ByteArray): ArtifactManifestEntry = requireNotNull(
        ArtifactManifestEntry.create(
            logicalRole = metadata.logicalRole,
            identity = metadata.artifact,
            byteCount = bytes.size.toLong(),
            contentSha256 = bytes.sha256Hex(),
            bundleId = metadata.bundleId,
            localRelativePath = metadata.destinationRelativePath,
            layoutRelativePath = metadata.layoutRelativePath,
        ),
    )

    private suspend fun <T> assertRootLockCancellationDoesNotReturn(
        root: String,
        operation: suspend () -> T,
    ) = coroutineScope {
        val lockEntered = CompletableDeferred<Unit>()
        val releaseLock = CompletableDeferred<Unit>()
        val lockHolder = async(start = CoroutineStart.UNDISPATCHED) {
            ArtifactRootLockCoordinator.withRoots(listOf(root)) {
                lockEntered.complete(Unit)
                releaseLock.await()
            }
        }
        lockEntered.await()
        var returned = false
        try {
            val attempt = async(start = CoroutineStart.UNDISPATCHED) {
                operation().also { returned = true }
            }
            attempt.cancelAndJoin()
            assertTrue(attempt.isCancelled)
            assertFalse(returned)
        } finally {
            releaseLock.complete(Unit)
            lockHolder.await()
        }
    }
}

private class SimulatedRootLifetimeCrash : RuntimeException()

private fun ByteArray.sha256Hex(): String = okio.ByteString.of(*this).sha256().hex()

private class TestServer(
    private val server: HttpServer,
) : AutoCloseable {
    val baseUrl: String = "http://127.0.0.1:${server.address.port}"

    override fun close() {
        server.stop(0)
    }
}

private fun withServer(handler: (HttpExchange) -> Unit): TestServer {
    val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    server.createContext("/") { exchange ->
        try {
            handler(exchange)
        } finally {
            exchange.close()
        }
    }
    server.start()
    return TestServer(server)
}

private fun HttpExchange.respond(status: Int, declaredLength: Long, body: ByteArray) {
    sendResponseHeaders(status, declaredLength)
    responseBody.use { output -> output.write(body) }
}

private inline fun <T> withTemporaryRoot(block: (File) -> T): T {
    val root = Files.createTempDirectory("caraml-download-test").toRealPath().toFile()
    return try {
        block(root)
    } finally {
        root.deleteRecursively()
    }
}

private fun modelFile(root: File, modelId: String, relativePath: String): File =
    File(File(root, "models/$modelId"), relativePath)

private class TestStoragePathProvider(
    private val root: File,
    private val storageResolutions: AtomicInteger? = null,
) : StoragePathProvider {
    override fun getModelsStorageDirectory(modelId: String): String =
        File(File(root, "models").apply { mkdirs() }, modelId)
            .also { storageResolutions?.incrementAndGet() }.absolutePath

    override fun getDatabasePath(): String = File(root, "caraml.db").absolutePath
    override fun fileExists(path: String): Boolean = File(path).exists()
    override fun getAvailableStorageBytes(): Long = Long.MAX_VALUE
    override fun getTotalStorageBytes(): Long = Long.MAX_VALUE
    override fun isModelFileReadable(path: String): Boolean = File(path).isFile
    override fun isDirectoryReadable(path: String): Boolean = File(path).isDirectory
    override fun getFileSize(path: String): Long = File(path).takeIf { it.exists() }?.length() ?: 0L

    override fun renameFile(from: String, to: String): Boolean = try {
        val source = File(from).toPath()
        val destination = File(to).toPath()
        destination.parent?.let(Files::createDirectories)
        try {
            Files.move(
                source,
                destination,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: Exception) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
        }
        true
    } catch (_: Exception) {
        false
    }

    override fun deleteDownloadedModelContent(modelId: String, localPath: String): Boolean =
        File(localPath).deleteRecursively()
}
