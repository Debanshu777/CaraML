package com.debanshu777.caraml.core.download.storage

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.debanshu777.caraml.core.download.DownloadArtifactRequest
import com.debanshu777.caraml.core.download.DownloadArtifactState
import com.debanshu777.caraml.core.download.DownloadBatchRequest
import com.debanshu777.caraml.core.download.DownloadBatchSnapshot
import com.debanshu777.caraml.core.download.DownloadBatchState
import com.debanshu777.caraml.core.download.DownloadFailureCode
import com.debanshu777.caraml.core.download.DownloadUserIntent
import com.debanshu777.caraml.core.download.downloadBatchArtifactId
import com.debanshu777.caraml.core.download.downloadBatchId
import com.debanshu777.caraml.core.download.pendingEvidence
import com.debanshu777.caraml.core.storage.getDatabaseBuilder
import com.debanshu777.caraml.core.storage.getRoomDatabase
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.huggingfacemanager.download.DownloadArtifactIdentity
import com.debanshu777.huggingfacemanager.download.DownloadMetadataDTO
import com.debanshu777.huggingfacemanager.download.artifactBundleId
import com.debanshu777.huggingfacemanager.download.immutableArtifactStorageLocation
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadDatabaseTest {
    @Test
    fun evidenceSurvivesDatabaseReopen() = runTest {
        val directory = Files.createTempDirectory("caraml-evidence-reopen")
        val path = directory.resolve("downloads.db").toString()
        val request = request()
        var database = getDownloadRoomDatabase(getDownloadDatabaseBuilder(path))
        val batchId = RoomDownloadTaskStore(database.downloadTaskDao()).create(request, 1L)
        database.close()

        database = getDownloadRoomDatabase(getDownloadDatabaseBuilder(path))
        try {
            assertEquals(request.evidence, RoomDownloadTaskStore(database.downloadTaskDao()).getBatch(batchId)!!.evidence)
        } finally {
            database.close()
        }
    }

    @Test
    fun evidenceDigestParticipatesInBatchIdentity() {
        val request = request()
        val identity = request.artifacts.single().metadata.artifact
        val changedIdentity = requireNotNull(
            DownloadArtifactIdentity.create(
                repositoryId = identity.repositoryId,
                immutableRevision = identity.immutableRevision,
                relativePath = identity.relativePath,
                remoteObjectId = "f".repeat(64),
                expectedBytes = identity.expectedBytes,
            ),
        )
        val changed = request.copy(evidence = pendingEvidence(changedIdentity))
        assertNotEquals(downloadBatchId(request), downloadBatchId(changed))
    }

    @Test
    fun sameArtifactsWithDistinctEvidenceCoexistAfterReopen() = runTest {
        val path = Files.createTempDirectory("caraml-evidence-batches").resolve("downloads.db").toString()
        val first = request()
        val identity = first.artifacts.single().metadata.artifact
        val changedIdentity = requireNotNull(
            DownloadArtifactIdentity.create(
                repositoryId = identity.repositoryId,
                immutableRevision = identity.immutableRevision,
                relativePath = identity.relativePath,
                remoteObjectId = "f".repeat(64),
                expectedBytes = identity.expectedBytes,
            ),
        )
        val second = first.copy(evidence = pendingEvidence(changedIdentity))
        var database = getDownloadRoomDatabase(getDownloadDatabaseBuilder(path))
        val firstBatchId: String
        val secondBatchId: String
        try {
            val store = RoomDownloadTaskStore(database.downloadTaskDao())
            firstBatchId = store.create(first, 1L)
            secondBatchId = store.create(second, 2L)
        } finally {
            database.close()
        }

        database = getDownloadRoomDatabase(getDownloadDatabaseBuilder(path))
        try {
            val store = RoomDownloadTaskStore(database.downloadTaskDao())
            val firstRestored = assertNotNull(store.getBatch(firstBatchId))
            val secondRestored = assertNotNull(store.getBatch(secondBatchId))
            assertNotEquals(firstBatchId, secondBatchId)
            assertEquals(first.evidence, firstRestored.evidence)
            assertEquals(second.evidence, secondRestored.evidence)
            assertEquals(first.artifacts.single(), firstRestored.artifacts.single().request)
            assertEquals(second.artifacts.single(), secondRestored.artifacts.single().request)
            assertNotEquals(firstRestored.artifacts.single().artifactId, secondRestored.artifacts.single().artifactId)
            assertEquals(2, database.downloadTaskDao().countBatches())
        } finally {
            database.close()
        }
    }

    @Test
    fun claimQuarantinesCurrentRowWithUnscopedDestination() = runTest {
        val path = Files.createTempDirectory("caraml-download-claim-scope").resolve("downloads.db").toString()
        var database = getDownloadRoomDatabase(getDownloadDatabaseBuilder(path))
        val artifactId: String
        try {
            val store = RoomDownloadTaskStore(database.downloadTaskDao())
            val batchId = store.create(request(), nowEpochMs = 1L)
            artifactId = requireNotNull(store.getBatch(batchId)).artifacts.single().artifactId
        } finally {
            database.close()
        }
        BundledSQLiteDriver().open(path).use { connection ->
            connection.prepare(
                "UPDATE download_artifact SET destination_relative_path = relative_path WHERE artifact_id = ?",
            ).use { statement ->
                statement.bindText(1, artifactId)
                statement.step()
            }
        }

        database = getDownloadRoomDatabase(getDownloadDatabaseBuilder(path))
        try {
            val store = RoomDownloadTaskStore(database.downloadTaskDao())
            assertFalse(store.claim(artifactId, "worker", nowEpochMs = 2L, expiresAtEpochMs = 100L))
            val persisted = database.downloadTaskDao().requireArtifact(artifactId)
            assertEquals(DownloadArtifactState.FAILED_TERMINAL.name, persisted.state)
            assertEquals(DownloadFailureCode.SECURE_PATH.name, persisted.failureCode)
            assertEquals(null, persisted.leaseOwner)
            assertEquals(
                DownloadBatchState.FAILED_TERMINAL.name,
                database.downloadTaskDao().requireBatch(persisted.batchId).state,
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun recoverableBatchesQuarantineCorruptRowsIndependentlyAcrossReopen() = runTest {
        val path = Files.createTempDirectory("caraml-download-corrupt-isolation").resolve("downloads.db").toString()
        var database = getDownloadRoomDatabase(getDownloadDatabaseBuilder(path))
        val batches = linkedMapOf<String, Pair<String, String>>()
        try {
            val store = RoomDownloadTaskStore(database.downloadTaskDao())
            listOf("case", "length", "bundle", "suffix", "evidence", "valid").forEachIndexed { index, key ->
                val batchId = store.create(
                    request(
                        relativePath = "weights/model-$key.gguf",
                        immutableRevision = ("${index + 1}".repeat(40)),
                    ),
                    nowEpochMs = index.toLong(),
                )
                val artifact = requireNotNull(store.getBatch(batchId)).artifacts.single()
                batches[key] = batchId to artifact.artifactId
            }
        } finally {
            database.close()
        }
        BundledSQLiteDriver().open(path).use { connection ->
            connection.execSQL(
                "UPDATE download_artifact SET bundle_id = upper(bundle_id) " +
                    "WHERE artifact_id = '${batches.getValue("case").second}'",
            )
            connection.prepare(
                "UPDATE download_artifact SET relative_path = ? WHERE artifact_id = ?",
            ).use { statement ->
                statement.bindText(1, "weights/${"x".repeat(1_100)}.gguf")
                statement.bindText(2, batches.getValue("length").second)
                statement.step()
            }
            val arbitraryBundle = "d".repeat(64)
            connection.execSQL(
                "UPDATE download_artifact SET bundle_id = '$arbitraryBundle', " +
                    "destination_relative_path = '.caraml-artifacts/$arbitraryBundle/weights/model-bundle.gguf' " +
                    "WHERE artifact_id = '${batches.getValue("bundle").second}'",
            )
            connection.execSQL(
                "UPDATE download_artifact SET destination_relative_path = destination_relative_path || '.extra' " +
                    "WHERE artifact_id = '${batches.getValue("suffix").second}'",
            )
            connection.execSQL(
                "UPDATE download_batch SET evidence_payload = NULL " +
                    "WHERE batch_id = '${batches.getValue("evidence").first}'",
            )
        }

        repeat(2) {
            database = getDownloadRoomDatabase(getDownloadDatabaseBuilder(path))
            try {
                val store = RoomDownloadTaskStore(database.downloadTaskDao())
                assertEquals(
                    listOf(batches.getValue("valid").first),
                    store.recoverableBatches().map(DownloadBatchSnapshot::batchId),
                )
                batches.filterKeys { it != "valid" }.values.forEach { (batchId, artifactId) ->
                    assertEquals(DownloadBatchState.FAILED_TERMINAL.name, database.downloadTaskDao().requireBatch(batchId).state)
                    val artifact = database.downloadTaskDao().requireArtifact(artifactId)
                    assertEquals(DownloadArtifactState.FAILED_TERMINAL.name, artifact.state)
                    assertEquals(DownloadFailureCode.SECURE_PATH.name, artifact.failureCode)
                    assertEquals(null, artifact.platformTaskId)
                    assertEquals(null, artifact.leaseOwner)
                    assertEquals(null, artifact.leaseExpiresAtEpochMs)
                }
            } finally {
                database.close()
            }
        }
    }

    @Test
    fun readPathsFilterMalformedTerminalRowsAndQuarantineMutableRowsAcrossReopen() = runTest {
        val path = Files.createTempDirectory("caraml-download-safe-reads").resolve("downloads.db").toString()
        var database = getDownloadRoomDatabase(getDownloadDatabaseBuilder(path))
        val terminalBatchId: String
        val mutableBatchId: String
        val validBatchId: String
        try {
            val store = RoomDownloadTaskStore(database.downloadTaskDao())
            terminalBatchId = store.create(request(relativePath = "weights/terminal.gguf"), 1L)
            mutableBatchId = store.create(
                request(relativePath = "weights/mutable.gguf", immutableRevision = "b".repeat(40)),
                2L,
            )
            validBatchId = store.create(
                request(relativePath = "weights/valid.gguf", immutableRevision = "c".repeat(40)),
                3L,
            )
        } finally {
            database.close()
        }
        BundledSQLiteDriver().open(path).use { connection ->
            connection.execSQL(
                "UPDATE download_batch SET state = 'FAILED_TERMINAL', failure_code = 'INTEGRITY', " +
                    "evidence_payload = NULL WHERE batch_id = '$terminalBatchId'",
            )
            connection.execSQL(
                "UPDATE download_artifact SET state = 'FAILED_TERMINAL', failure_code = 'INTEGRITY' " +
                    ", destination_relative_path = relative_path WHERE batch_id = '$terminalBatchId'",
            )
            connection.execSQL(
                "UPDATE download_batch SET evidence_payload = NULL WHERE batch_id = '$mutableBatchId'",
            )
        }

        repeat(2) {
            database = getDownloadRoomDatabase(getDownloadDatabaseBuilder(path))
            try {
                val store = RoomDownloadTaskStore(database.downloadTaskDao())

                assertEquals(
                    listOf(validBatchId),
                    store.observeForModel("owner/model").first().map(DownloadBatchSnapshot::batchId),
                )
                assertNull(store.getBatch(terminalBatchId))
                assertNull(store.getBatch(mutableBatchId))
                assertNotNull(store.getBatch(validBatchId))
                assertEquals(
                    DownloadBatchState.FAILED_TERMINAL.name,
                    database.downloadTaskDao().requireBatch(mutableBatchId).state,
                )
                assertEquals(
                    DownloadFailureCode.SECURE_PATH.name,
                    database.downloadTaskDao().requireBatch(mutableBatchId).failureCode,
                )
            } finally {
                database.close()
            }
        }
    }

    @Test
    fun fullyNullPersistedEvidenceIsRejectedWithoutFabrication() = runTest {
        val path = Files.createTempDirectory("caraml-null-evidence").resolve("downloads.db").toString()
        val request = request()
        var database = getDownloadRoomDatabase(getDownloadDatabaseBuilder(path))
        val batchId = RoomDownloadTaskStore(database.downloadTaskDao()).create(request, 1L)
        database.close()
        clearPersistedEvidence(path)

        database = getDownloadRoomDatabase(getDownloadDatabaseBuilder(path))
        try {
            assertNull(RoomDownloadTaskStore(database.downloadTaskDao()).getBatch(batchId))
            assertEquals(
                DownloadBatchState.FAILED_TERMINAL.name,
                database.downloadTaskDao().requireBatch(batchId).state,
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun partiallyPopulatedPersistedEvidenceIsRejected() = runTest {
        val path = Files.createTempDirectory("caraml-partial-evidence").resolve("downloads.db").toString()
        var database = getDownloadRoomDatabase(getDownloadDatabaseBuilder(path))
        val batchId = RoomDownloadTaskStore(database.downloadTaskDao()).create(request(), 1L)
        database.close()
        BundledSQLiteDriver().open(path).use { connection ->
            connection.prepare("UPDATE download_batch SET evidence_payload = NULL WHERE batch_id = ?").use { statement ->
                statement.bindText(1, batchId)
                statement.step()
            }
        }

        database = getDownloadRoomDatabase(getDownloadDatabaseBuilder(path))
        try {
            assertNull(RoomDownloadTaskStore(database.downloadTaskDao()).getBatch(batchId))
        } finally {
            database.close()
        }
    }

    @Test
    fun persistedEvidenceWithoutRemoteObjectIdentityIsRejected() = runTest {
        val path = Files.createTempDirectory("caraml-missing-object-evidence").resolve("downloads.db").toString()
        var database = getDownloadRoomDatabase(getDownloadDatabaseBuilder(path))
        val batchId = RoomDownloadTaskStore(database.downloadTaskDao()).create(request(), 1L)
        database.close()
        clearPersistedEvidence(path)
        BundledSQLiteDriver().open(path).use { connection ->
            connection.prepare("UPDATE download_artifact SET remote_object_id = NULL WHERE batch_id = ?").use { statement ->
                statement.bindText(1, batchId)
                statement.step()
            }
        }

        database = getDownloadRoomDatabase(getDownloadDatabaseBuilder(path))
        try {
            assertNull(RoomDownloadTaskStore(database.downloadTaskDao()).getBatch(batchId))
        } finally {
            database.close()
        }
    }

    @Test
    fun enqueueIsIdempotentAndPersistsExactIdentity() = runTest {
        val database = openDatabase("identity")
        val store = RoomDownloadTaskStore(database.downloadTaskDao())
        val request = request()
        try {
            val firstId = store.create(request, nowEpochMs = 10L)
            val secondId = store.create(request, nowEpochMs = 20L)
            val stored = assertNotNull(store.getBatch(firstId))

            assertEquals(firstId, secondId)
            assertEquals(1, stored.artifacts.size)
            assertEquals(request.artifacts.single().metadata.artifact, stored.artifacts.single().request.metadata.artifact)
            assertEquals(10L, database.downloadTaskDao().requireBatch(firstId).createdAtEpochMs)
        } finally {
            database.close()
        }
    }

    @Test
    fun onlyOneLeaseCanClaimTheSameArtifact() = runTest {
        val database = openDatabase("lease")
        val store = RoomDownloadTaskStore(database.downloadTaskDao())
        try {
            val batchId = store.create(request(), nowEpochMs = 1L)
            val artifactId = store.getBatch(batchId)!!.artifacts.single().artifactId

            assertTrue(store.claim(artifactId, owner = "worker-a", nowEpochMs = 2L, expiresAtEpochMs = 100L))
            assertFalse(store.claim(artifactId, owner = "worker-b", nowEpochMs = 3L, expiresAtEpochMs = 101L))
        } finally {
            database.close()
        }
    }

    @Test
    fun cancellationCheckpointAtomicallyTransitionsClaimAndReleasesExactLease() = runTest {
        val database = openDatabase("cancellation-checkpoint")
        val store = RoomDownloadTaskStore(database.downloadTaskDao())
        try {
            val batchId = store.create(request(), nowEpochMs = 1L)
            val artifactId = store.getBatch(batchId)!!.artifacts.single().artifactId
            assertTrue(store.claim(artifactId, owner = "worker-a", nowEpochMs = 2L, expiresAtEpochMs = 100L))

            assertFalse(
                store.checkpointCancellation(
                    batchId = batchId,
                    artifactId = artifactId,
                    owner = "stale-worker",
                    nowEpochMs = 3L,
                ),
            )
            assertTrue(
                store.checkpointCancellation(
                    batchId = batchId,
                    artifactId = artifactId,
                    owner = "worker-a",
                    nowEpochMs = 4L,
                ),
            )

            val persisted = database.downloadTaskDao().requireArtifact(artifactId)
            assertEquals(DownloadArtifactState.FAILED_RETRYABLE.name, persisted.state)
            assertEquals(DownloadFailureCode.NETWORK.name, persisted.failureCode)
            assertNull(persisted.leaseOwner)
            assertNull(persisted.leaseExpiresAtEpochMs)
            assertFalse(store.checkpointCancellation(batchId, artifactId, "worker-a", 5L))
        } finally {
            database.close()
        }
    }

    @Test
    fun userStopPausesOnlyTheExactBoundPlatformGeneration() = runTest {
        val database = openDatabase("platform-stop-generation")
        val store = RoomDownloadTaskStore(database.downloadTaskDao())
        try {
            val batchId = store.create(request(), nowEpochMs = 1L)
            val artifactId = store.getBatch(batchId)!!.artifacts.single().artifactId
            assertTrue(store.bindPlatformTask(batchId, "uidt-old", 2L))
            assertTrue(store.bindPlatformTask(batchId, "uidt-replacement", 3L))

            assertFalse(store.pausePlatformTask(batchId, "uidt-old", 4L))
            assertEquals(DownloadUserIntent.RUN.name, database.downloadTaskDao().requireBatch(batchId).userIntent)
            assertEquals("uidt-replacement", database.downloadTaskDao().requireArtifact(artifactId).platformTaskId)

            assertTrue(store.pausePlatformTask(batchId, "uidt-replacement", 5L))
            assertEquals(DownloadUserIntent.PAUSE.name, database.downloadTaskDao().requireBatch(batchId).userIntent)
            val persisted = database.downloadTaskDao().requireArtifact(artifactId)
            assertEquals(DownloadArtifactState.PAUSED.name, persisted.state)
            assertNull(persisted.platformTaskId)
            assertNull(persisted.leaseOwner)
            assertFalse(store.pausePlatformTask(batchId, "uidt-replacement", 6L))
        } finally {
            database.close()
        }
    }

    @Test
    fun reopeningDatabasePreservesPausedCheckpointAndIntent() = runTest {
        val directory = Files.createTempDirectory("caraml-download-db-reopen")
        val dbPath = directory.resolve("downloads.db").toString()
        var database = getDownloadRoomDatabase(getDownloadDatabaseBuilder(dbPath))
        val batchId: String
        val artifactId: String
        try {
            val store = RoomDownloadTaskStore(database.downloadTaskDao())
            batchId = store.create(request(), nowEpochMs = 1L)
            artifactId = store.getBatch(batchId)!!.artifacts.single().artifactId
            store.setUserIntent(batchId, DownloadUserIntent.PAUSE, nowEpochMs = 2L)
            store.updateProgress(
                artifactId = artifactId,
                bytesReceived = 256L,
                entityTag = "etag-1",
                lastModified = null,
                nowEpochMs = 3L,
            )
            store.transitionArtifact(artifactId, DownloadArtifactState.PAUSED, failureCode = null, nowEpochMs = 4L)
        } finally {
            database.close()
        }

        database = getDownloadRoomDatabase(getDownloadDatabaseBuilder(dbPath))
        try {
            val restored = RoomDownloadTaskStore(database.downloadTaskDao()).getBatch(batchId)!!
            assertEquals(DownloadUserIntent.PAUSE, restored.userIntent)
            assertEquals(DownloadArtifactState.PAUSED, restored.artifacts.single().state)
            assertEquals(256L, restored.artifacts.single().bytesReceived)
            assertEquals("etag-1", database.downloadTaskDao().requireArtifact(artifactId).entityTag)
        } finally {
            database.close()
        }
    }

    @Test
    fun clearingDownloadQueueLeavesPrimaryModelDatabaseUntouched() = runTest {
        val directory = Files.createTempDirectory("caraml-download-db-isolation")
        val appDatabase = getRoomDatabase(getDatabaseBuilder(directory.resolve("caraml.db").toString()))
        val downloadDatabase = getDownloadRoomDatabase(
            getDownloadDatabaseBuilder(directory.resolve("downloads.db").toString()),
        )
        try {
            appDatabase.localModelDao().insert(
                LocalModelEntity(
                    modelId = "owner/model",
                    filename = "model.gguf",
                    localPath = "/private/model.gguf",
                    sizeBytes = 1L,
                    downloadedAt = 1L,
                    author = null,
                    libraryName = null,
                    pipelineTag = null,
                ),
            )
            val store = RoomDownloadTaskStore(downloadDatabase.downloadTaskDao())
            store.create(request(), nowEpochMs = 1L)
            store.clearAll()

            assertEquals(0, downloadDatabase.downloadTaskDao().countBatches())
            assertEquals("owner/model", appDatabase.localModelDao().getAllDownloadedFiles().first().single().modelId)
        } finally {
            downloadDatabase.close()
            appDatabase.close()
        }
    }

    @Test
    fun partiallyCompletedCancellationCanBeEnqueuedAgain() = runTest {
        val database = openDatabase("partial-cancel")
        val store = RoomDownloadTaskStore(database.downloadTaskDao())
        val request = twoArtifactRequest()
        try {
            val batchId = store.create(request, nowEpochMs = 1L)
            val artifacts = store.getBatch(batchId)!!.artifacts
            val completed = artifacts.first { it.request.primary }
            val cancelled = artifacts.first { !it.request.primary }

            assertTrue(store.claim(completed.artifactId, "worker", 2L, 100L))
            assertTrue(store.transitionArtifact(completed.artifactId, DownloadArtifactState.VERIFYING, null, 3L))
            assertTrue(store.transitionArtifact(completed.artifactId, DownloadArtifactState.COMPLETED, null, 4L))
            assertTrue(store.transitionArtifact(cancelled.artifactId, DownloadArtifactState.CANCELLED, null, 5L))
            assertEquals(DownloadBatchState.CANCELLED, store.getBatch(batchId)!!.state)

            store.create(request, nowEpochMs = 6L)

            val reactivated = store.getBatch(batchId)!!
            assertEquals(DownloadBatchState.QUEUED, reactivated.state)
            assertEquals(DownloadArtifactState.COMPLETED, reactivated.artifacts.first { it.request.primary }.state)
            assertEquals(DownloadArtifactState.QUEUED, reactivated.artifacts.first { !it.request.primary }.state)
        } finally {
            database.close()
        }
    }

    private fun openDatabase(suffix: String): DownloadDatabase {
        val path = Files.createTempDirectory("caraml-download-$suffix").resolve("downloads.db")
        return getDownloadRoomDatabase(getDownloadDatabaseBuilder(path.toString()))
    }

    private fun request(
        relativePath: String = "weights/model.gguf",
        immutableRevision: String = "a".repeat(40),
    ): DownloadBatchRequest {
        val identity = requireNotNull(
            DownloadArtifactIdentity.create(
                repositoryId = "owner/model",
                immutableRevision = immutableRevision,
                relativePath = relativePath,
                remoteObjectId = "b".repeat(64),
                expectedBytes = 1_024L,
            ),
        )
        return DownloadBatchRequest(
            ownerModelId = identity.repositoryId,
            modelType = "text",
            artifacts = listOf(
                DownloadArtifactRequest(
                    metadata = DownloadMetadataDTO(
                        artifact = identity,
                        logicalRole = "model",
                        sizeBytes = identity.expectedBytes,
                        author = "owner",
                        libraryName = "gguf",
                        pipelineTag = "text-generation",
                    ),
                    primary = true,
                ),
            ),
            evidence = pendingEvidence(identity),
            downloadForLaterConfirmed = false,
            displayName = "Example model",
        )
    }

    private fun twoArtifactRequest(): DownloadBatchRequest {
        val primary = requireNotNull(
            DownloadArtifactIdentity.create(
                repositoryId = "owner/model",
                immutableRevision = "a".repeat(40),
                relativePath = "weights/model.gguf",
                remoteObjectId = "b".repeat(64),
                expectedBytes = 1_024L,
            ),
        )
        val component = requireNotNull(
            DownloadArtifactIdentity.create(
                repositoryId = "owner/component",
                immutableRevision = "c".repeat(40),
                relativePath = "tokenizer.gguf",
                remoteObjectId = "d".repeat(64),
                expectedBytes = 512L,
            ),
        )
        val bundleId = requireNotNull(artifactBundleId(listOf(primary, component)))
        fun metadata(identity: DownloadArtifactIdentity, role: String) = DownloadMetadataDTO(
            artifact = identity,
            logicalRole = role,
            sizeBytes = identity.expectedBytes,
            author = "owner",
            libraryName = "gguf",
            pipelineTag = "text-generation",
            destinationRelativePath = immutableArtifactStorageLocation(identity, bundleId).localRelativePath,
            bundleId = bundleId,
        )
        return DownloadBatchRequest(
            ownerModelId = primary.repositoryId,
            modelType = "text",
            artifacts = listOf(
                DownloadArtifactRequest(metadata(primary, "model"), primary = true),
                DownloadArtifactRequest(metadata(component, "tokenizer"), primary = false),
            ),
            evidence = pendingEvidence(listOf(primary, component)),
            downloadForLaterConfirmed = false,
            displayName = "Example model",
        )
    }

    private fun clearPersistedEvidence(path: String) {
        BundledSQLiteDriver().open(path).use { connection ->
            connection.execSQL(
                """
                UPDATE download_batch
                SET evidence_state = NULL, evidence_schema_version = NULL,
                    evidence_payload = NULL, evidence_sha256 = NULL
                """.trimIndent(),
            )
        }
    }
}
