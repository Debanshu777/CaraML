package com.debanshu777.caraml.core.download.storage

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.debanshu777.caraml.core.download.DownloadArtifactRequest
import com.debanshu777.caraml.core.download.DownloadArtifactState
import com.debanshu777.caraml.core.download.DownloadBatchRequest
import com.debanshu777.caraml.core.download.DownloadBatchState
import com.debanshu777.caraml.core.download.DownloadUserIntent
import com.debanshu777.caraml.core.download.downloadBatchId
import com.debanshu777.caraml.core.download.pendingEvidence
import com.debanshu777.caraml.core.recommendation.storage.InstalledEvidenceState
import com.debanshu777.caraml.core.storage.getDatabaseBuilder
import com.debanshu777.caraml.core.storage.getRoomDatabase
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.huggingfacemanager.download.DownloadArtifactIdentity
import com.debanshu777.huggingfacemanager.download.DownloadMetadataDTO
import com.debanshu777.huggingfacemanager.download.artifactBundleId
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
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
    fun migrationOneToTwoPreservesBatchWithNullableEvidenceColumns() {
        val path = Files.createTempDirectory("caraml-download-migration").resolve("downloads.db").toString()
        BundledSQLiteDriver().open(path).use { connection ->
            createVersionOneTables(connection)
            connection.execSQL(
                """
                INSERT INTO download_batch (
                    batch_id, owner_model_id, model_type, display_name, state, user_intent, failure_code,
                    download_for_later_confirmed, created_at_epoch_ms, updated_at_epoch_ms
                ) VALUES ('batch-1', 'owner/model', 'text', 'Example model', 'QUEUED', 'RUN', NULL, 0, 1, 1)
                """.trimIndent(),
            )

            DOWNLOAD_MIGRATION_1_2.migrate(connection)

            connection.prepare(
                """
                SELECT batch_id, evidence_state, evidence_schema_version, evidence_payload, evidence_sha256
                FROM download_batch WHERE batch_id = 'batch-1'
                """.trimIndent(),
            ).use { statement ->
                assertTrue(statement.step())
                assertEquals("batch-1", statement.getText(0))
                assertTrue(statement.isNull(1))
                assertTrue(statement.isNull(2))
                assertTrue(statement.isNull(3))
                assertTrue(statement.isNull(4))
            }
        }
    }

    @Test
    fun fullyNullMigratedEvidenceSynthesizesPendingEvidenceFromArtifacts() = runTest {
        val path = Files.createTempDirectory("caraml-null-evidence").resolve("downloads.db").toString()
        val request = request()
        var database = getDownloadRoomDatabase(getDownloadDatabaseBuilder(path))
        val batchId = RoomDownloadTaskStore(database.downloadTaskDao()).create(request, 1L)
        database.close()
        clearPersistedEvidence(path)

        database = getDownloadRoomDatabase(getDownloadDatabaseBuilder(path))
        try {
            val restored = assertNotNull(RoomDownloadTaskStore(database.downloadTaskDao()).getBatch(batchId))
            assertEquals(InstalledEvidenceState.REQUIRES_ENRICHMENT, restored.evidence.state)
            assertEquals(pendingEvidence(request.artifacts.map { it.metadata.artifact }), restored.evidence)
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
            assertFailsWith<IllegalStateException> {
                RoomDownloadTaskStore(database.downloadTaskDao()).getBatch(batchId)
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun migratedEvidenceWithoutRemoteObjectIdentityIsRejected() = runTest {
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
            assertFailsWith<IllegalStateException> {
                RoomDownloadTaskStore(database.downloadTaskDao()).getBatch(batchId)
            }
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

    private fun request(): DownloadBatchRequest {
        val identity = requireNotNull(
            DownloadArtifactIdentity.create(
                repositoryId = "owner/model",
                immutableRevision = "a".repeat(40),
                relativePath = "weights/model.gguf",
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
            destinationRelativePath = identity.relativePath,
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

    private fun createVersionOneTables(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS download_batch (
                batch_id TEXT NOT NULL PRIMARY KEY,
                owner_model_id TEXT NOT NULL,
                model_type TEXT NOT NULL,
                display_name TEXT NOT NULL,
                state TEXT NOT NULL,
                user_intent TEXT NOT NULL,
                failure_code TEXT,
                download_for_later_confirmed INTEGER NOT NULL,
                created_at_epoch_ms INTEGER NOT NULL,
                updated_at_epoch_ms INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS download_artifact (
                artifact_id TEXT NOT NULL PRIMARY KEY,
                batch_id TEXT NOT NULL,
                repository_id TEXT NOT NULL,
                immutable_revision TEXT NOT NULL,
                relative_path TEXT NOT NULL,
                remote_object_id TEXT,
                expected_bytes INTEGER NOT NULL,
                logical_role TEXT NOT NULL,
                destination_relative_path TEXT NOT NULL,
                bundle_id TEXT NOT NULL,
                is_primary INTEGER NOT NULL,
                author TEXT,
                library_name TEXT,
                pipeline_tag TEXT,
                context_length INTEGER,
                state TEXT NOT NULL,
                bytes_received INTEGER NOT NULL,
                entity_tag TEXT,
                last_modified TEXT,
                failure_code TEXT,
                retry_count INTEGER NOT NULL,
                platform_task_id TEXT,
                lease_owner TEXT,
                lease_expires_at_epoch_ms INTEGER,
                staging_token TEXT NOT NULL,
                updated_at_epoch_ms INTEGER NOT NULL,
                FOREIGN KEY(batch_id) REFERENCES download_batch(batch_id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS index_download_batch_owner_model_id_updated_at_epoch_ms " +
                "ON download_batch (owner_model_id, updated_at_epoch_ms)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS index_download_artifact_batch_id ON download_artifact (batch_id)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS index_download_artifact_state_updated_at_epoch_ms " +
                "ON download_artifact (state, updated_at_epoch_ms)",
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
