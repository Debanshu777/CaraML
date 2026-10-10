package com.debanshu777.caraml.core.download.storage

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.debanshu777.caraml.core.download.DownloadManagerArtifactTransfer
import com.debanshu777.huggingfacemanager.download.DownloadManager
import com.debanshu777.huggingfacemanager.download.StoragePathProvider
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import okio.Buffer
import com.debanshu777.caraml.core.download.ArtifactTransfer
import com.debanshu777.caraml.core.download.BatchFinalizer
import com.debanshu777.caraml.core.download.DownloadBatchRunner
import com.debanshu777.caraml.core.download.DownloadRunResult
import com.debanshu777.huggingfacemanager.download.DownloadProgressDTO
import com.debanshu777.huggingfacemanager.download.DownloadResumeMetadata
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
    fun ownedCleanupPauseRejectsNewerRunOrCancelAndKeepsCheckpoint() = runTest {
        val database = getDownloadRoomDatabase(getDownloadDatabaseBuilder(
            Files.createTempDirectory("caraml-cleanup-pause").resolve("downloads.db").toString()))
        try {
            val store = RoomDownloadTaskStore(database.downloadTaskDao())
            val id = store.create(request().copy(displayName = "fixed-owned-marker"), 1L)
            val artifact = store.getBatch(id)!!.artifacts.single().artifactId
            assertTrue(store.claim(artifact, "worker", 2L, 20L))
            assertTrue(store.updateTransferProgress(artifact, "worker", 256L, "validator", null, 3L, 20L))
            val (first, firstVersion) = store.runningPauseSnapshot(id)!!
            assertTrue(store.setUserIntent(id, DownloadUserIntent.RUN, firstVersion))
            assertFalse(store.pauseRunningSnapshot(first, firstVersion, 4L))
            val (second, secondVersion) = store.runningPauseSnapshot(id)!!
            assertTrue(store.setUserIntent(id, DownloadUserIntent.CANCEL, secondVersion))
            assertFalse(store.pauseRunningSnapshot(second, secondVersion, 5L))
            assertEquals(DownloadUserIntent.CANCEL, store.getBatch(id)!!.userIntent)
            assertEquals(DownloadArtifactState.RUNNING, store.getBatch(id)!!.artifacts.single().state)
            assertEquals("worker", database.downloadTaskDao().requireArtifact(artifact).leaseOwner)
            assertTrue(store.setUserIntent(id, DownloadUserIntent.RUN, 6L))
            val (latest, latestVersion) = store.runningPauseSnapshot(id)!!
            assertTrue(store.pauseRunningSnapshot(latest, latestVersion, 7L))
            val paused = store.getBatch(id)!!
            assertEquals(DownloadUserIntent.PAUSE, paused.userIntent)
            assertEquals(DownloadArtifactState.PAUSED, paused.artifacts.single().state)
            assertEquals(256L, paused.bytesReceived)
            assertEquals("validator", paused.artifacts.single().entityTag)
            assertNull(database.downloadTaskDao().requireArtifact(artifact).leaseOwner)
        } finally { database.close() }
    }

    @Test
    fun pausedResumeVersionRejectsSameMillisIntentAndClockRollbackRefresh() = runTest {
        val database = getDownloadRoomDatabase(getDownloadDatabaseBuilder(
            Files.createTempDirectory("caraml-resume-version").resolve("downloads.db").toString()))
        try {
            val dao = database.downloadTaskDao()
            val store = RoomDownloadTaskStore(dao)
            val id = store.create(request(), 1L)
            val artifact = store.getBatch(id)!!.artifacts.single().artifactId
            assertTrue(store.setUserIntent(id, DownloadUserIntent.PAUSE, 2L))
            assertTrue(store.transitionArtifact(artifact, DownloadArtifactState.PAUSED, null, 3L))
            val (first, firstVersion) = store.pausedResumeSnapshot(id)!!
            assertTrue(store.setUserIntent(id, DownloadUserIntent.PAUSE, firstVersion))
            assertFalse(store.resumePausedSnapshot(first, firstVersion, 10L))
            val (second, secondVersion) = store.pausedResumeSnapshot(id)!!
            assertTrue(secondVersion > firstVersion)
            assertTrue(store.setUserIntent(id, DownloadUserIntent.PAUSE, 1L))
            dao.updateBatchState(id, DownloadBatchState.PAUSED.name, null, 1L)
            assertFalse(store.resumePausedSnapshot(second, secondVersion, 10L))
            val (latest, latestVersion) = store.pausedResumeSnapshot(id)!!
            assertTrue(latestVersion > secondVersion)
            assertTrue(store.resumePausedSnapshot(latest, latestVersion, 1L))
            assertEquals(DownloadUserIntent.RUN, store.getBatch(id)!!.userIntent)
        } finally { database.close() }
    }

    @Test
    fun explicitPausedResumePreservesCheckpointVerificationAndRejectsNewerIntent() = runTest {
        val database = getDownloadRoomDatabase(getDownloadDatabaseBuilder(
            Files.createTempDirectory("caraml-explicit-resume").resolve("downloads.db").toString()))
        try {
            val store = RoomDownloadTaskStore(database.downloadTaskDao())
            val id = store.create(request(), 1L)
            val artifact = store.getBatch(id)!!.artifacts.single().artifactId
            assertTrue(store.claim(artifact, "worker", 2L, 20L))
            assertTrue(store.updateTransferProgress(artifact, "worker", 256L, "validator", null, 3L, 20L))
            assertTrue(store.setUserIntent(id, DownloadUserIntent.PAUSE, 4L))
            assertTrue(store.transitionArtifact(artifact, DownloadArtifactState.PAUSED, null, 5L))
            val (paused, version) = store.pausedResumeSnapshot(id)!!
            assertTrue(store.setUserIntent(id, DownloadUserIntent.PAUSE, 6L))
            assertFalse(store.resumePausedSnapshot(paused, version, 7L))
            val (newPause, newVersion) = store.pausedResumeSnapshot(id)!!
            assertTrue(store.setUserIntent(id, DownloadUserIntent.CANCEL, 8L))
            assertFalse(store.resumePausedSnapshot(newPause, newVersion, 9L))
            assertEquals(DownloadUserIntent.CANCEL, store.getBatch(id)!!.userIntent)
            assertTrue(store.setUserIntent(id, DownloadUserIntent.PAUSE, 10L))
            val (selected, selectedVersion) = store.pausedResumeSnapshot(id)!!
            assertTrue(store.resumePausedSnapshot(selected, selectedVersion, 11L))
            val queued = store.getBatch(id)!!
            assertEquals(DownloadBatchState.QUEUED, queued.state)
            assertEquals(DownloadUserIntent.RUN, queued.userIntent)
            assertEquals(DownloadArtifactState.QUEUED, queued.artifacts.single().state)
            assertEquals(256L, queued.bytesReceived)
            assertEquals("validator", queued.artifacts.single().entityTag)
            assertTrue(store.claim(artifact, "verify", 12L, 30L))
            assertTrue(store.transitionArtifact(artifact, DownloadArtifactState.VERIFYING, null, 13L))
            assertTrue(store.setUserIntent(id, DownloadUserIntent.PAUSE, 14L))
            val (verifying, verifyVersion) = store.pausedResumeSnapshot(id)!!
            assertTrue(store.resumePausedSnapshot(verifying, verifyVersion, 15L))
            assertEquals(DownloadBatchState.VERIFYING, store.getBatch(id)!!.state)
            assertEquals(DownloadArtifactState.VERIFYING, store.getBatch(id)!!.artifacts.single().state)
            assertEquals(256L, store.getBatch(id)!!.bytesReceived)
        } finally { database.close() }
    }

    @Test
    fun queuedArtifactClaimAtomicallyHonorsPauseAndCancelBeforeArtifactTransitions() = runTest {
        val database = getDownloadRoomDatabase(getDownloadDatabaseBuilder(
            Files.createTempDirectory("caraml-claim-intent").resolve("downloads.db").toString()))
        try {
            val store = RoomDownloadTaskStore(database.downloadTaskDao())
            val id = store.create(request(), 1L)
            val artifact = store.getBatch(id)!!.artifacts.single().artifactId
            assertTrue(store.setUserIntent(id, DownloadUserIntent.PAUSE, 2L))
            assertFalse(store.claim(artifact, "worker", 3L, 30L))
            assertEquals(DownloadArtifactState.QUEUED, store.getBatch(id)!!.artifacts.single().state)
            assertNull(database.downloadTaskDao().requireArtifact(artifact).leaseOwner)
            assertTrue(store.setUserIntent(id, DownloadUserIntent.CANCEL, 4L))
            assertFalse(store.claim(artifact, "worker", 5L, 30L))
            assertEquals(DownloadUserIntent.CANCEL, store.getBatch(id)!!.userIntent)
            assertEquals(DownloadArtifactState.QUEUED, store.getBatch(id)!!.artifacts.single().state)
            assertNull(database.downloadTaskDao().requireArtifact(artifact).leaseOwner)
            assertTrue(store.setUserIntent(id, DownloadUserIntent.RUN, 6L))
            assertTrue(store.claim(artifact, "worker", 7L, 30L))
            assertEquals(DownloadArtifactState.RUNNING, store.getBatch(id)!!.artifacts.single().state)
            assertEquals("worker", database.downloadTaskDao().requireArtifact(artifact).leaseOwner)
        } finally { database.close() }
    }

    @Test
    fun atomicNetworkRetryPreservesCheckpointAndHonorsConcurrentPauseAndCancel() = runTest {
        val database = getDownloadRoomDatabase(getDownloadDatabaseBuilder(
            Files.createTempDirectory("caraml-network-retry").resolve("downloads.db").toString()))
        try {
            val store = RoomDownloadTaskStore(database.downloadTaskDao())
            val id = store.create(request(), 1L)
            val artifact = store.getBatch(id)!!.artifacts.single().artifactId
            assertTrue(store.claim(artifact, "active", 2L, 20L))
            assertTrue(store.updateTransferProgress(artifact, "active", 256L, "strong-validator", null, 3L, 20L))
            assertTrue(store.transitionArtifact(artifact, DownloadArtifactState.FAILED_RETRYABLE, DownloadFailureCode.NETWORK, 4L))
            // The terminal transfer transition already clears its lease; production finally-release is idempotent.
            assertNull(database.downloadTaskDao().requireArtifact(artifact).leaseOwner)
            val failed = store.getBatch(id)!!
            assertEquals(DownloadBatchState.FAILED_RETRYABLE, failed.state)
            assertTrue(store.setUserIntent(id, DownloadUserIntent.PAUSE, 6L))
            val paused = store.getBatch(id)
            assertFalse(store.retryNetworkIfRunningIntent(id, 7L))
            assertEquals(paused, store.getBatch(id))
            assertTrue(store.setUserIntent(id, DownloadUserIntent.CANCEL, 8L))
            val cancelled = store.getBatch(id)
            assertFalse(store.retryNetworkIfRunningIntent(id, 9L))
            assertEquals(cancelled, store.getBatch(id))
            assertTrue(store.setUserIntent(id, DownloadUserIntent.RUN, 10L))
            assertTrue(store.retryNetworkIfRunningIntent(id, 11L))
            val queued = store.getBatch(id)!!
            assertEquals(DownloadUserIntent.RUN, queued.userIntent)
            assertEquals(DownloadBatchState.QUEUED, queued.state)
            assertEquals(DownloadArtifactState.QUEUED, queued.artifacts.single().state)
            assertEquals(256L, queued.bytesReceived)
            assertEquals("strong-validator", queued.artifacts.single().entityTag)
            assertFalse(store.retryNetworkIfRunningIntent(id, 12L))
        } finally { database.close() }
    }

    @Test
    fun insertOnlyNeverReactivatesExistingTerminalOrPausedWork() = runTest {
        val path = Files.createTempDirectory("caraml-insert-only").resolve("downloads.db").toString()
        val database = getDownloadRoomDatabase(getDownloadDatabaseBuilder(path))
        try {
            val store = RoomDownloadTaskStore(database.downloadTaskDao())
            val request = request()
            val id = store.create(request, 1L)
            val artifact = store.getBatch(id)!!.artifacts.single().artifactId
            assertTrue(store.setUserIntent(id, DownloadUserIntent.PAUSE, 2L))
            assertTrue(store.transitionArtifact(artifact, DownloadArtifactState.PAUSED, null, 2L))
            val paused = store.getBatch(id)
            assertFalse(store.createNewOnly(request.copy(displayName = "fresh-debug-marker"), 3L).second)
            assertEquals(paused, store.getBatch(id))
            assertTrue(store.setUserIntent(id, DownloadUserIntent.CANCEL, 4L))
            assertTrue(store.transitionArtifact(artifact, DownloadArtifactState.CANCELLED, null, 4L))
            val cancelled = store.getBatch(id)
            assertFalse(store.createNewOnly(request.copy(displayName = "another-marker"), 5L).second)
            assertEquals(cancelled, store.getBatch(id))
        } finally { database.close() }
    }

    @Test
    fun concurrentInsertOnlyHasOneFreshOwnerAndPersistsItsMarkerBeforeScheduling() = runTest {
        val path = Files.createTempDirectory("caraml-insert-race").resolve("downloads.db").toString()
        var database = getDownloadRoomDatabase(getDownloadDatabaseBuilder(path))
        val request = request()
        val store = RoomDownloadTaskStore(database.downloadTaskDao())
        val attempts = (1..2).map { index -> async {
            val marker = "private-marker-$index"
            marker to store.createNewOnly(request.copy(displayName = marker), index.toLong())
        } }.awaitAll()
        val winner = attempts.single { it.second.second }
        assertEquals(1, database.downloadTaskDao().countBatches())
        assertEquals(winner.first, store.getBatch(winner.second.first)!!.displayName)
        database.close()
        database = getDownloadRoomDatabase(getDownloadDatabaseBuilder(path))
        try {
            val recovered = RoomDownloadTaskStore(database.downloadTaskDao()).getBatch(winner.second.first)!!
            assertEquals(winner.first, recovered.displayName)
            assertEquals(DownloadBatchState.QUEUED, recovered.state)
            assertEquals(DownloadUserIntent.RUN, recovered.userIntent)
            assertNull(recovered.artifacts.single().platformTaskId)
        } finally { database.close() }
    }

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
    fun ownedLegacyCheckpointRestartPersistsSmallerProgressAndResumesAfterInterruption() = runTest {
        val database = openDatabase("owned-legacy-restart")
        val store = RoomDownloadTaskStore(database.downloadTaskDao())
        try {
            val batchId = store.create(request(), 1L)
            val artifactId = store.getBatch(batchId)!!.artifacts.single().artifactId
            assertTrue(store.claim(artifactId, "legacy-owner", 2L, 100L))
            assertTrue(store.updateProgress(artifactId, 768L, null, null, 3L))
            assertFalse(store.restartTransferCheckpoint(artifactId, "stale-owner", 768L, 4L))
            assertFalse(store.restartTransferCheckpoint(artifactId, "legacy-owner", 512L, 4L))
            assertFalse(store.restartTransferCheckpoint(artifactId, "legacy-owner", 768L, 101L))
            assertTrue(store.transitionArtifact(artifactId, DownloadArtifactState.FAILED_RETRYABLE, DownloadFailureCode.NETWORK, 5L))
            assertFalse(store.restartTransferCheckpoint(artifactId, "legacy-owner", 768L, 6L))
            var attempt = 0
            var now = 1000L
            val transfer = object : ArtifactTransfer {
                override fun download(metadata: DownloadMetadataDTO, resumeMetadata: DownloadResumeMetadata?): Flow<DownloadProgressDTO> = flow {
                    if (++attempt == 1) {
                        assertNull(resumeMetadata)
                        emit(DownloadProgressDTO(128L, 1024L, 12.5f, entityTag = "new-validator"))
                        throw IllegalStateException("synthetic interruption")
                    }
                    assertEquals(DownloadResumeMetadata(128L, "new-validator", null), resumeMetadata)
                    emit(DownloadProgressDTO(1024L, 1024L, 100f, localPath = "/synthetic/published", contentSha256 = "b".repeat(64)))
                }
            }
            val runner = DownloadBatchRunner(store, transfer, BatchFinalizer {}, { now += 1000L; now }, { "new-owner" })
            assertEquals(DownloadRunResult.Retry(DownloadFailureCode.NETWORK), runner.run(batchId) {})
            val checkpoint = assertNotNull(store.getBatch(batchId)).artifacts.single()
            assertEquals(128L, checkpoint.bytesReceived)
            assertEquals("new-validator", checkpoint.entityTag)
            assertFalse(store.updateProgress(artifactId, 64L, "stale", null, now + 1L))
            assertEquals(DownloadRunResult.Completed, runner.run(batchId) {})
            assertEquals(DownloadBatchState.COMPLETED, store.getBatch(batchId)!!.state)
        } finally { database.close() }
    }

    @Test
    fun http200RangeRestartPersistsSmallerRoomCheckpointAndResumesWithNewValidator() = runTest {
        val directory = Files.createTempDirectory("caraml-room-http-restart").toRealPath().toFile()
        val payload = ByteArray(8192) { (it % 127).toByte() }
        val hash = Buffer().write(payload).sha256().hex()
        val identity = requireNotNull(DownloadArtifactIdentity.create("owner/model", "a".repeat(40), "weights/model.gguf", hash, payload.size.toLong()))
        val metadata = DownloadMetadataDTO(artifact = identity, logicalRole = "model", sizeBytes = identity.expectedBytes,
            author = "owner", libraryName = "gguf", pipelineTag = "text-generation")
        val request = request().copy(artifacts = listOf(DownloadArtifactRequest(metadata, primary = true)), evidence = pendingEvidence(identity))
        val database = openDatabase("http-range-restart")
        val store = RoomDownloadTaskStore(database.downloadTaskDao())
        val paths = RoomRestartStoragePathProvider(directory)
        val staged = File(paths.getModelsStorageDirectory(identity.repositoryId), metadata.destinationRelativePath + ".part")
        staged.parentFile.mkdirs(); staged.writeBytes(payload.copyOfRange(0, 6144))
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.responseHeaders.add("ETag", "new-validator")
            if (requests.incrementAndGet() == 1) {
                assertEquals("bytes=6144-", exchange.requestHeaders.getFirst("Range"))
                assertEquals("old-validator", exchange.requestHeaders.getFirst("If-Range"))
                exchange.sendResponseHeaders(200, payload.size.toLong())
                try { exchange.responseBody.use { body -> body.write(payload, 0, 4096); body.flush(); Thread.sleep(100); body.write(payload, 4096, 4096) } }
                catch (_: java.io.IOException) { }
                finally { exchange.close() }
            } else {
                assertEquals("bytes=4096-", exchange.requestHeaders.getFirst("Range"))
                assertEquals("new-validator", exchange.requestHeaders.getFirst("If-Range"))
                exchange.responseHeaders.add("Content-Range", "bytes 4096-8191/8192")
                exchange.sendResponseHeaders(206, 4096L)
                exchange.responseBody.use { it.write(payload, 4096, 4096) }
            }
        }
        server.start()
        try {
            val batchId = store.create(request, 1L)
            val artifactId = store.getBatch(batchId)!!.artifacts.single().artifactId
            assertTrue(store.claim(artifactId, "old-owner", 2L, 100L))
            assertTrue(store.updateProgress(artifactId, 6144L, "old-validator", null, 3L))
            assertTrue(store.transitionArtifact(artifactId, DownloadArtifactState.FAILED_RETRYABLE, DownloadFailureCode.NETWORK, 4L))
            val manager = DownloadManager(paths, "http://127.0.0.1:${server.address.port}")
            var now = 1000L
            val delegate = DownloadManagerArtifactTransfer(manager)
            val attempts = AtomicInteger()
            val transfer = object : ArtifactTransfer {
                override suspend fun isPublished(metadata: DownloadMetadataDTO) = delegate.isPublished(metadata)
                override fun download(metadata: DownloadMetadataDTO, resumeMetadata: DownloadResumeMetadata?): Flow<DownloadProgressDTO> = flow {
                    val attempt = attempts.incrementAndGet()
                    delegate.download(metadata, resumeMetadata).collect { progress ->
                        emit(progress)
                        if (attempt == 1 && progress.localPath == null && progress.bytesReceived > 0L) {
                            throw java.io.IOException("synthetic interruption after persisted progress")
                        }
                    }
                }
            }
            val runner = DownloadBatchRunner(store, transfer,
                BatchFinalizer { assertTrue(manager.publishBundle(identity.repositoryId, listOf(metadata))) },
                { now += 1000L; now }, { "new-owner" })
            assertEquals(DownloadRunResult.Retry(DownloadFailureCode.NETWORK), runner.run(batchId) {})
            val checkpoint = store.getBatch(batchId)!!.artifacts.single()
            assertEquals(4096L, checkpoint.bytesReceived)
            assertEquals("new-validator", checkpoint.entityTag)
            assertEquals(DownloadRunResult.Completed, runner.run(batchId) {})
            assertEquals(DownloadBatchState.COMPLETED, store.getBatch(batchId)!!.state)
            val published = assertNotNull(manager.validatedArtifacts(identity.repositoryId)).entries.single()
            assertEquals(hash, published.contentSha256)
            assertEquals(payload.size.toLong(), published.byteCount)
            assertEquals(2, requests.get())
        } finally { server.stop(0); database.close() }
    }

    @Test
    fun transferProgressRenewsOnlyLiveMatchingRunningOwner() = runTest {
        val database = openDatabase("owned-lease-renewal")
        val store = RoomDownloadTaskStore(database.downloadTaskDao())
        try {
            val batchId = store.create(request(), 1L)
            val artifactId = store.getBatch(batchId)!!.artifacts.single().artifactId
            assertTrue(store.claim(artifactId, "owner", 2L, 100L))
            assertFalse(store.updateTransferProgress(artifactId, "foreign", 128L, "etag", null, 3L, 200L))
            assertTrue(store.updateTransferProgress(artifactId, "owner", 128L, "etag", null, 3L, 200L))
            assertEquals(200L, database.downloadTaskDao().requireArtifact(artifactId).leaseExpiresAtEpochMs)
            assertFalse(store.updateTransferProgress(artifactId, "owner", 256L, "etag", null, 201L, 300L))
            assertTrue(store.setUserIntent(batchId, DownloadUserIntent.PAUSE, 4L))
            assertFalse(store.updateTransferProgress(artifactId, "owner", 256L, "etag", null, 5L, 300L))
            assertTrue(store.transitionArtifact(artifactId, DownloadArtifactState.PAUSED, null, 6L))
            assertFalse(store.updateTransferProgress(artifactId, "owner", 256L, "etag", null, 7L, 300L))
        } finally { database.close() }
    }

    @Test
    fun missingCompletedArtifactCanBeAtomicallyRequeuedForReinstall() = runTest {
        val database = openDatabase("completed-reinstall")
        val store = RoomDownloadTaskStore(database.downloadTaskDao())
        try {
            val batchId = store.create(request(), nowEpochMs = 1L)
            val artifactId = store.getBatch(batchId)!!.artifacts.single().artifactId
            assertTrue(store.claim(artifactId, owner = "worker", nowEpochMs = 2L, expiresAtEpochMs = 100L))
            assertTrue(store.updateProgress(artifactId, 1_024L, "etag", "modified", 3L))
            assertTrue(store.transitionArtifact(artifactId, DownloadArtifactState.VERIFYING, null, 4L))
            assertTrue(store.transitionArtifact(artifactId, DownloadArtifactState.COMPLETED, null, 5L))

            assertTrue(store.requeueMissingCompletedArtifact(artifactId, nowEpochMs = 6L))
            assertFalse(store.requeueMissingCompletedArtifact(artifactId, nowEpochMs = 7L))

            val restored = assertNotNull(store.getBatch(batchId))
            assertEquals(DownloadBatchState.QUEUED, restored.state)
            with(restored.artifacts.single()) {
                assertEquals(DownloadArtifactState.QUEUED, state)
                assertEquals(0L, bytesReceived)
                assertNull(entityTag)
                assertNull(lastModified)
                assertNull(platformTaskId)
            }
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
    fun completionTransitionsOnlyTheExactBoundPlatformGeneration() = runTest {
        val database = openDatabase("platform-completion-generation")
        val store = RoomDownloadTaskStore(database.downloadTaskDao())
        try {
            val batchId = store.create(request(), nowEpochMs = 1L)
            val artifact = store.getBatch(batchId)!!.artifacts.single()
            assertTrue(store.bindPlatformTask(batchId, "ios-old", 2L))
            assertTrue(store.claim(artifact.artifactId, "ios-import", 3L, Long.MAX_VALUE))
            assertTrue(store.bindPlatformTask(batchId, "ios-replacement", 4L))

            assertFalse(
                store.transitionPlatformTask(
                    artifactId = artifact.artifactId,
                    platformTaskId = "ios-old",
                    state = DownloadArtifactState.VERIFYING,
                    failureCode = null,
                    completedBytes = artifact.expectedBytes,
                    nowEpochMs = 5L,
                ),
            )
            assertTrue(
                store.transitionPlatformTask(
                    artifactId = artifact.artifactId,
                    platformTaskId = "ios-replacement",
                    state = DownloadArtifactState.VERIFYING,
                    failureCode = null,
                    completedBytes = artifact.expectedBytes,
                    nowEpochMs = 6L,
                ),
            )

            val persisted = database.downloadTaskDao().requireArtifact(artifact.artifactId)
            assertEquals(DownloadArtifactState.VERIFYING.name, persisted.state)
            assertEquals(artifact.expectedBytes, persisted.bytesReceived)
            assertNull(persisted.platformTaskId)
            assertNull(persisted.leaseOwner)
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

private class RoomRestartStoragePathProvider(private val root: File) : StoragePathProvider {
    override fun getModelsStorageDirectory(modelId: String) = File(File(root, "models"), modelId).apply { mkdirs() }.absolutePath
    override fun getDatabasePath() = File(root, "caraml.db").absolutePath
    override fun fileExists(path: String) = File(path).exists()
    override fun getAvailableStorageBytes() = Long.MAX_VALUE
    override fun getTotalStorageBytes() = Long.MAX_VALUE
    override fun isModelFileReadable(path: String) = File(path).isFile
    override fun isDirectoryReadable(path: String) = File(path).isDirectory
    override fun getFileSize(path: String) = File(path).length()
    override fun renameFile(from: String, to: String) = false
    override fun deleteDownloadedModelContent(modelId: String, localPath: String) = false
}
