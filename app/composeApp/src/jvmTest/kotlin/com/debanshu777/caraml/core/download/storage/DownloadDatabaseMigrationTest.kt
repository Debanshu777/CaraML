package com.debanshu777.caraml.core.download.storage

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.debanshu777.caraml.core.download.DownloadArtifactRequest
import com.debanshu777.caraml.core.download.DownloadArtifactState
import com.debanshu777.caraml.core.download.DownloadBatchRequest
import com.debanshu777.caraml.core.download.DownloadBatchState
import com.debanshu777.caraml.core.download.DownloadUserIntent
import com.debanshu777.caraml.core.download.downloadBatchArtifactId
import com.debanshu777.caraml.core.download.downloadBatchId
import com.debanshu777.caraml.core.download.pendingEvidence
import com.debanshu777.caraml.core.download.transfer.DownloadArtifactIdentity
import com.debanshu777.caraml.core.download.transfer.DownloadMetadataDTO
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadDatabaseMigrationTest {
    @Test
    fun versionThreeUpgradePreservesCheckpointAndMarksInterruptedCancellationForCleanup() = runTest {
        listOf(false, true).forEach { unsafe ->
            val path = historicalDatabase(3)
            val request = request()
            val batchId = downloadBatchId(request)
            val artifactId = downloadBatchArtifactId(batchId, request.artifacts.single())
            BundledSQLiteDriver().open(path).use { connection ->
                insertHistoricalDownload(connection, 3, request,
                    destination = if (unsafe) "../outside.gguf" else request.artifacts.single().metadata.destinationRelativePath,
                    state = "CANCELLED")
                connection.execSQL("UPDATE download_batch SET user_intent = 'CANCEL'")
            }
            val database = getDownloadRoomDatabase(getDownloadDatabaseBuilder(path))
            try {
                val dao = database.downloadTaskDao()
                val store = RoomDownloadTaskStore(dao)
                assertTrue(dao.requireBatch(batchId).cancellationCleanupPending)
                assertCheckpoint(dao.requireArtifact(artifactId))
                assertEquals(request.evidence.payload, dao.requireBatch(batchId).evidencePayload)
                val pending = store.pendingCancellation(batchId)
                if (unsafe) {
                    assertNull(pending)
                    assertFalse(dao.requireBatch(batchId).cancellationCleanupPending)
                    assertEquals("../outside.gguf", dao.requireArtifact(artifactId).destinationRelativePath)
                    assertCheckpoint(dao.requireArtifact(artifactId))
                } else {
                    assertNotNull(pending)
                    assertTrue(store.completeCancellationCleanup(pending, 20L))
                    assertEquals(0L, dao.requireArtifact(artifactId).bytesReceived)
                    assertEquals("CANCELLED", dao.requireBatch(batchId).state)
                }
            } finally { database.close() }
        }
    }

    @Test
    fun freshDownloadAfterLegacyUpgradeKeepsHistoricalTaskAndCheckpointUnchanged() = runTest {
        // v1 and early v2 used global artifact IDs; a57e63ac scoped IDs to batches within v2.
        listOf(1 to false, 2 to false, 2 to true).forEach { (version, batchScopedArtifactId) ->
            val path = historicalDatabase(version)
            val currentRequest = request()
            val legacyDestination = currentRequest.artifacts.single().metadata.artifact.relativePath
            val (legacyBatchId, legacyArtifactId) = historicalTaskIds(
                version, currentRequest, legacyDestination, batchScopedArtifactId,
            )
            BundledSQLiteDriver().open(path).use { connection ->
                insertHistoricalDownload(
                    connection, version, currentRequest, destination = legacyDestination,
                    batchId = legacyBatchId, artifactId = legacyArtifactId,
                )
            }

            val database = getDownloadRoomDatabase(getDownloadDatabaseBuilder(path))
            try {
                val dao = database.downloadTaskDao()
                val store = RoomDownloadTaskStore(dao)
                val legacyBatch = dao.requireBatch(legacyBatchId)
                val legacyArtifact = dao.requireArtifact(legacyArtifactId)
                assertEquals("FAILED_TERMINAL", legacyBatch.state)
                assertEquals("SECURE_PATH", legacyArtifact.failureCode)
                assertCheckpoint(legacyArtifact)

                val freshBatchId = store.create(currentRequest, nowEpochMs = 20L)
                val fresh = assertNotNull(store.getBatch(freshBatchId))
                val freshArtifact = fresh.artifacts.single()
                assertNotEquals(legacyBatchId, freshBatchId)
                assertNotEquals(legacyArtifactId, freshArtifact.artifactId)
                assertEquals(2, dao.countBatches())
                assertEquals(DownloadBatchState.QUEUED, fresh.state)
                assertEquals(0L, freshArtifact.bytesReceived)
                assertEquals(currentRequest.artifacts.single(), freshArtifact.request)
                assertNull(freshArtifact.entityTag)
                assertTrue(store.claim(freshArtifact.artifactId, "new-worker", 30L, 40L))
                assertEquals(legacyBatch, dao.requireBatch(legacyBatchId))
                assertEquals(legacyArtifact, dao.requireArtifact(legacyArtifactId))
            } finally {
                database.close()
            }
        }
    }

    @Test
    fun versionOneUpgradePreservesCheckpointsAndQuarantinesLegacyWrites() = runTest {
        val path = historicalDatabase(1)
        val request = request()
        val batchId = downloadBatchId(request)
        val artifactId = downloadBatchArtifactId(batchId, request.artifacts.single())
        BundledSQLiteDriver().open(path).use { connection ->
            insertHistoricalDownload(connection, 1, request, destination = "weights/model.gguf")
        }

        repeat(2) {
            val database = getDownloadRoomDatabase(getDownloadDatabaseBuilder(path))
            try {
                val dao = database.downloadTaskDao()
                val batch = dao.requireBatch(batchId)
                val artifact = dao.requireArtifact(artifactId)
                assertEquals(1, dao.countBatches())
                assertEquals("FAILED_TERMINAL", batch.state)
                assertEquals("SECURE_PATH", batch.failureCode)
                assertEquals("PAUSE", batch.userIntent)
                assertEquals("FAILED_TERMINAL", artifact.state)
                assertEquals("SECURE_PATH", artifact.failureCode)
                assertEquals("weights/model.gguf", artifact.destinationRelativePath)
                assertCheckpoint(artifact)
                assertNull(batch.evidencePayload)
                assertNull(artifact.platformTaskId)
                assertNull(artifact.leaseOwner)
                assertNull(artifact.leaseExpiresAtEpochMs)
                val store = RoomDownloadTaskStore(dao)
                assertEquals(emptyList(), store.recoverableBatches())
                assertFalse(store.claim(artifactId, "new-worker", 10L, 20L))
            } finally {
                database.close()
            }
        }
    }

    @Test
    fun versionTwoUpgradePreservesExactPausedWorkAndItsEvidence() = runTest {
        val path = historicalDatabase(2)
        val request = request()
        val batchId = downloadBatchId(request)
        val artifactId = downloadBatchArtifactId(batchId, request.artifacts.single())
        BundledSQLiteDriver().open(path).use { connection ->
            insertHistoricalDownload(connection, 2, request)
        }

        repeat(2) {
            val database = getDownloadRoomDatabase(getDownloadDatabaseBuilder(path))
            try {
                val dao = database.downloadTaskDao()
                val store = RoomDownloadTaskStore(dao)
                val restored = assertNotNull(store.getBatch(batchId))
                assertEquals(DownloadBatchState.PAUSED, restored.state)
                assertEquals(DownloadUserIntent.PAUSE, restored.userIntent)
                assertEquals(DownloadArtifactState.PAUSED, restored.artifacts.single().state)
                assertEquals(request.evidence, restored.evidence)
                assertEquals(request.artifacts.single(), restored.artifacts.single().request)
                assertCheckpoint(dao.requireArtifact(artifactId))
                assertEquals(listOf(batchId), store.recoverableBatches().map { it.batchId })
                assertFalse(store.claim(artifactId, "new-worker", 10L, 20L))
            } finally {
                database.close()
            }
        }
    }

    @Test
    fun versionTwoUpgradeQuarantinesScopedPathAliasesButKeepsCompletedHistory() = runTest {
        val path = historicalDatabase(2)
        val unsafe = request()
        val completed = request("history.gguf")
        val unsafeId = downloadBatchId(unsafe)
        val completedId = downloadBatchId(completed)
        val unsafeArtifactId = downloadBatchArtifactId(unsafeId, unsafe.artifacts.single())
        val completedArtifactId = downloadBatchArtifactId(completedId, completed.artifacts.single())
        val aliasedDestination = unsafe.artifacts.single().metadata.destinationRelativePath + "-other"
        BundledSQLiteDriver().open(path).use { connection ->
            insertHistoricalDownload(connection, 2, unsafe, destination = aliasedDestination)
            insertHistoricalDownload(connection, 2, completed, destination = "history.gguf", state = "COMPLETED")
        }

        val database = getDownloadRoomDatabase(getDownloadDatabaseBuilder(path))
        try {
            val dao = database.downloadTaskDao()
            assertEquals(2, dao.countBatches())
            assertEquals("FAILED_TERMINAL", dao.requireBatch(unsafeId).state)
            val quarantined = dao.requireArtifact(unsafeArtifactId)
            assertEquals("SECURE_PATH", quarantined.failureCode)
            assertEquals(aliasedDestination, quarantined.destinationRelativePath)
            assertCheckpoint(quarantined)
            assertNull(quarantined.platformTaskId)
            assertNull(quarantined.leaseOwner)
            assertEquals("COMPLETED", dao.requireBatch(completedId).state)
            assertEquals("COMPLETED", dao.requireArtifact(completedArtifactId).state)
            assertEquals("history.gguf", dao.requireArtifact(completedArtifactId).destinationRelativePath)
            val store = RoomDownloadTaskStore(dao)
            assertEquals(emptyList(), store.recoverableBatches())
            assertFalse(store.claim(unsafeArtifactId, "new-worker", 10L, 20L))
            assertFalse(store.claim(completedArtifactId, "new-worker", 10L, 20L))
        } finally {
            database.close()
        }
    }

    @Test
    fun unknownDatabaseVersionFailsWithoutDestroyingQueue() = runTest {
        val path = historicalDatabase(2)
        val request = request()
        BundledSQLiteDriver().open(path).use { connection ->
            insertHistoricalDownload(connection, 2, request)
            connection.execSQL("PRAGMA user_version = 99")
        }
        val database = getDownloadRoomDatabase(getDownloadDatabaseBuilder(path))
        try {
            assertFailsWith<IllegalStateException> { database.downloadTaskDao().countBatches() }
        } finally {
            database.close()
        }
        BundledSQLiteDriver().open(path).use { connection ->
            connection.prepare("SELECT batch_id FROM download_batch").use { statement ->
                check(statement.step())
                assertEquals(downloadBatchId(request), statement.getText(0))
            }
        }
    }

    private fun assertCheckpoint(artifact: DownloadArtifactEntity) {
        assertEquals(256L, artifact.bytesReceived)
        assertEquals("strong-etag", artifact.entityTag)
        assertEquals("last-modified", artifact.lastModified)
        assertEquals(2, artifact.retryCount)
        assertEquals(artifact.artifactId, artifact.stagingToken)
        assertEquals(5L, artifact.updatedAtEpochMs)
    }

    private fun request(filename: String = "weights/model.gguf"): DownloadBatchRequest {
        val identity = requireNotNull(DownloadArtifactIdentity.create(
            "owner/model", "a".repeat(40), filename, "sha256:${"b".repeat(64)}", 1_024L,
        ))
        return DownloadBatchRequest(
            ownerModelId = identity.repositoryId,
            modelType = "text",
            artifacts = listOf(DownloadArtifactRequest(
                DownloadMetadataDTO(identity, "model", 1_024L, "owner", "gguf", "text-generation"),
                primary = true,
            )),
            evidence = pendingEvidence(identity),
            downloadForLaterConfirmed = false,
            displayName = "Example model",
        )
    }

    /** Schemas from 24453e7c (v1) and fa38cc3d (v2); v3 retained the v2 schema. Includes actual Room index names. */
    private fun historicalDatabase(version: Int): String {
        val path = Files.createTempDirectory("caraml-download-migration-$version").resolve("downloads.db").toString()
        BundledSQLiteDriver().open(path).use { connection ->
            val evidenceColumns = if (version >= 2) {
                "evidence_state TEXT, evidence_schema_version INTEGER, evidence_payload TEXT, evidence_sha256 TEXT,"
            } else ""
            connection.execSQL("""
                CREATE TABLE download_batch (
                    batch_id TEXT NOT NULL PRIMARY KEY, owner_model_id TEXT NOT NULL,
                    model_type TEXT NOT NULL, display_name TEXT NOT NULL, state TEXT NOT NULL,
                    user_intent TEXT NOT NULL, failure_code TEXT,
                    $evidenceColumns
                    download_for_later_confirmed INTEGER NOT NULL, created_at_epoch_ms INTEGER NOT NULL,
                    updated_at_epoch_ms INTEGER NOT NULL
                )
            """.trimIndent())
            connection.execSQL("""
                CREATE TABLE download_artifact (
                    artifact_id TEXT NOT NULL PRIMARY KEY, batch_id TEXT NOT NULL,
                    repository_id TEXT NOT NULL, immutable_revision TEXT NOT NULL, relative_path TEXT NOT NULL,
                    remote_object_id TEXT, expected_bytes INTEGER NOT NULL, logical_role TEXT NOT NULL,
                    destination_relative_path TEXT NOT NULL, bundle_id TEXT NOT NULL, is_primary INTEGER NOT NULL,
                    author TEXT, library_name TEXT, pipeline_tag TEXT, context_length INTEGER,
                    state TEXT NOT NULL, bytes_received INTEGER NOT NULL, entity_tag TEXT, last_modified TEXT,
                    failure_code TEXT, retry_count INTEGER NOT NULL, platform_task_id TEXT, lease_owner TEXT,
                    lease_expires_at_epoch_ms INTEGER, staging_token TEXT NOT NULL, updated_at_epoch_ms INTEGER NOT NULL,
                    FOREIGN KEY(batch_id) REFERENCES download_batch(batch_id) ON UPDATE NO ACTION ON DELETE CASCADE
                )
            """.trimIndent())
            connection.execSQL("CREATE INDEX index_download_batch_owner_model_id_updated_at_epoch_ms ON download_batch (owner_model_id, updated_at_epoch_ms)")
            connection.execSQL("CREATE INDEX index_download_artifact_batch_id ON download_artifact (batch_id)")
            connection.execSQL("CREATE INDEX index_download_artifact_state_lease_expires_at_epoch_ms ON download_artifact (state, lease_expires_at_epoch_ms)")
            connection.execSQL("PRAGMA user_version = $version")
        }
        return path
    }

    private fun insertHistoricalDownload(
        connection: SQLiteConnection,
        version: Int,
        request: DownloadBatchRequest,
        destination: String = request.artifacts.single().metadata.destinationRelativePath,
        state: String = "PAUSED",
        batchId: String = downloadBatchId(request),
        artifactId: String = downloadBatchArtifactId(batchId, request.artifacts.single()),
    ) {
        val artifact = request.artifacts.single()
        val metadata = artifact.metadata
        connection.prepare("""
            INSERT INTO download_batch (
                batch_id, owner_model_id, model_type, display_name, state, user_intent,
                download_for_later_confirmed, created_at_epoch_ms, updated_at_epoch_ms
            ) VALUES (?, 'owner/model', 'text', 'Example model', ?, 'PAUSE', 0, 1, 5)
        """.trimIndent()).use { statement ->
            statement.bindText(1, batchId)
            statement.bindText(2, state)
            statement.step()
        }
        if (version >= 2) {
            connection.prepare("""
                UPDATE download_batch SET evidence_state = ?, evidence_schema_version = ?,
                    evidence_payload = ?, evidence_sha256 = ? WHERE batch_id = ?
            """.trimIndent()).use { statement ->
                statement.bindText(1, request.evidence.state.name)
                statement.bindLong(2, request.evidence.schemaVersion.toLong())
                statement.bindText(3, request.evidence.payload)
                statement.bindText(4, request.evidence.sha256)
                statement.bindText(5, batchId)
                statement.step()
            }
        }
        connection.prepare("""
            INSERT INTO download_artifact (
                artifact_id, batch_id, repository_id, immutable_revision, relative_path, remote_object_id,
                expected_bytes, logical_role, destination_relative_path, bundle_id, is_primary,
                author, library_name, pipeline_tag, state, bytes_received, entity_tag, last_modified,
                retry_count, platform_task_id, lease_owner, lease_expires_at_epoch_ms, staging_token, updated_at_epoch_ms
            ) VALUES (?, ?, 'owner/model', ?, ?, ?, 1024, 'model', ?, ?, 1, 'owner', 'gguf', 'text-generation',
                ?, 256, 'strong-etag', 'last-modified', 2, 'old-platform-task', 'old-worker', 100, ?, 5)
        """.trimIndent()).use { statement ->
            listOf(artifactId, batchId, metadata.artifact.immutableRevision, metadata.artifact.relativePath,
                requireNotNull(metadata.artifact.remoteObjectId), destination, metadata.bundleId, state, artifactId,
            ).forEachIndexed { index, value -> statement.bindText(index + 1, value) }
            statement.step()
        }
    }

    /** Exact ID algorithms from 24453e7c, fa38cc3d and a57e63ac, using the persisted old destination. */
    private fun historicalTaskIds(
        version: Int,
        request: DownloadBatchRequest,
        destination: String,
        batchScopedArtifactId: Boolean,
    ): Pair<String, String> {
        val artifact = request.artifacts.single()
        val metadata = artifact.metadata
        val identity = metadata.artifact
        val taskId = historicalSha256(
            identity.repositoryId, identity.immutableRevision, identity.relativePath,
            identity.remoteObjectId.orEmpty(), identity.expectedBytes.toString(), metadata.logicalRole,
            destination, metadata.bundleId, artifact.primary.toString(),
        )
        val batchFields = listOf(request.ownerModelId, request.modelType) +
            (if (version >= 2) listOf(request.evidence.sha256) else emptyList()) + taskId
        val batchId = historicalSha256(*batchFields.toTypedArray())
        return batchId to if (batchScopedArtifactId) historicalSha256(batchId, taskId) else taskId
    }

    private fun historicalSha256(vararg values: String): String {
        val buffer = Buffer()
        values.forEach { value ->
            val bytes = value.encodeToByteArray()
            buffer.writeInt(bytes.size)
            buffer.write(bytes)
        }
        return buffer.snapshot().sha256().hex()
    }
}
