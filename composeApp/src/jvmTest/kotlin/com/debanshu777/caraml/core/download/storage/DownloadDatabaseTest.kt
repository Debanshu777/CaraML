package com.debanshu777.caraml.core.download.storage

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.debanshu777.caraml.core.download.DownloadArtifactRequest
import com.debanshu777.caraml.core.download.DownloadArtifactState
import com.debanshu777.caraml.core.download.DownloadBatchRequest
import com.debanshu777.caraml.core.download.DownloadBatchState
import com.debanshu777.caraml.core.download.DownloadFailureCode
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
            connection.execSQL(
                """
                INSERT INTO download_artifact (
                    artifact_id, batch_id, repository_id, immutable_revision, relative_path,
                    expected_bytes, logical_role, destination_relative_path, bundle_id, is_primary,
                    state, bytes_received, retry_count, staging_token, updated_at_epoch_ms
                ) VALUES (
                    'legacy-artifact-id', 'batch-1', 'owner/model', 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    'weights/model.gguf', 1024, 'model', 'weights/model.gguf',
                    'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc', 1,
                    'QUEUED', 0, 0, 'legacy-staging-token', 1
                )
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
            connection.prepare("SELECT artifact_id FROM download_artifact WHERE batch_id = 'batch-1'").use { statement ->
                assertTrue(statement.step())
                assertEquals("legacy-artifact-id", statement.getText(0))
            }
        }
    }

    @Test
    fun migrationTwoToThreeQuarantinesUnscopedWorkButKeepsCompletedLegacyReadOnlyAfterReopen() = runTest {
        val path = Files.createTempDirectory("caraml-download-storage-migration").resolve("downloads.db").toString()
        createVersionTwoDatabase(path)
        BundledSQLiteDriver().open(path).use { connection ->
            insertVersionTwoArtifact(
                connection = connection,
                batchId = "1".repeat(64),
                artifactId = "a".repeat(64),
                batchState = "VERIFYING",
                artifactState = "QUEUED",
                destination = "weights/model.gguf",
            )
            insertVersionTwoArtifact(
                connection = connection,
                batchId = "2".repeat(64),
                artifactId = "b".repeat(64),
                batchState = "VERIFYING",
                artifactState = "VERIFYING",
                destination = "weights/model.gguf",
            )
            insertVersionTwoArtifact(
                connection = connection,
                batchId = "3".repeat(64),
                artifactId = "c".repeat(64),
                batchState = "COMPLETED",
                artifactState = "COMPLETED",
                destination = "weights/model.gguf",
            )
            insertVersionTwoArtifact(
                connection = connection,
                batchId = "4".repeat(64),
                artifactId = "d".repeat(64),
                batchState = "QUEUED",
                artifactState = "QUEUED",
                destination = ".caraml-artifacts/${"c".repeat(64)}/weights/model.gguf",
            )
        }

        repeat(2) {
            val database = getDownloadRoomDatabase(getDownloadDatabaseBuilder(path))
            try {
                val store = RoomDownloadTaskStore(database.downloadTaskDao())
                listOf("1".repeat(64), "2".repeat(64)).forEach { batchId ->
                    val quarantined = assertNotNull(store.getBatch(batchId))
                    assertEquals(DownloadBatchState.FAILED_TERMINAL, quarantined.state)
                    assertEquals(DownloadFailureCode.SECURE_PATH, quarantined.failureCode)
                    assertEquals(DownloadArtifactState.FAILED_TERMINAL, quarantined.artifacts.single().state)
                    assertEquals(DownloadFailureCode.SECURE_PATH, quarantined.artifacts.single().failureCode)
                    assertEquals(null, database.downloadTaskDao().requireArtifact(quarantined.artifacts.single().artifactId).leaseOwner)
                    assertEquals(null, quarantined.artifacts.single().platformTaskId)
                }
                val completed = assertNotNull(store.getBatch("3".repeat(64)))
                assertEquals(DownloadBatchState.COMPLETED, completed.state)
                assertEquals(DownloadArtifactState.COMPLETED, completed.artifacts.single().state)
                assertFalse(completed.artifacts.single().request.metadata.usesImmutableStorageLayout)

                val scoped = assertNotNull(store.getBatch("4".repeat(64)))
                assertEquals(DownloadBatchState.QUEUED, scoped.state)
                assertTrue(scoped.artifacts.single().request.metadata.usesImmutableStorageLayout)
                assertEquals(listOf("4".repeat(64)), store.recoverableBatches().map { it.batchId })
            } finally {
                database.close()
            }
        }
    }

    @Test
    fun claimFailsClosedWhenPersistedDestinationIsChangedToLegacyAfterMigration() = runTest {
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
            assertEquals(DownloadArtifactState.QUEUED.name, persisted.state)
            assertEquals(null, persisted.leaseOwner)
        } finally {
            database.close()
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

    private fun createVersionTwoDatabase(path: String) {
        BundledSQLiteDriver().open(path).use { connection ->
            createVersionOneTables(connection)
            DOWNLOAD_MIGRATION_1_2.migrate(connection)
            connection.execSQL("DROP INDEX IF EXISTS index_download_artifact_state_updated_at_epoch_ms")
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS index_download_artifact_state_lease_expires_at_epoch_ms " +
                    "ON download_artifact (state, lease_expires_at_epoch_ms)",
            )
            connection.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            connection.execSQL(
                "INSERT OR REPLACE INTO room_master_table (id, identity_hash) " +
                    "VALUES (42, 'e137d927c60c40eb309fa2b5fc0d8270')",
            )
            connection.execSQL("PRAGMA user_version = 2")
        }
    }

    private fun insertVersionTwoArtifact(
        connection: SQLiteConnection,
        batchId: String,
        artifactId: String,
        batchState: String,
        artifactState: String,
        destination: String,
    ) {
        connection.prepare(
            """
            INSERT INTO download_batch (
                batch_id, owner_model_id, model_type, display_name, state, user_intent, failure_code,
                evidence_state, evidence_schema_version, evidence_payload, evidence_sha256,
                download_for_later_confirmed, created_at_epoch_ms, updated_at_epoch_ms
            ) VALUES (?, 'owner/model', 'text', 'Model', ?, 'RUN', NULL, NULL, NULL, NULL, NULL, 0, 1, 1)
            """.trimIndent(),
        ).use { statement ->
            statement.bindText(1, batchId)
            statement.bindText(2, batchState)
            statement.step()
        }
        connection.prepare(
            """
            INSERT INTO download_artifact (
                artifact_id, batch_id, repository_id, immutable_revision, relative_path, remote_object_id,
                expected_bytes, logical_role, destination_relative_path, bundle_id, is_primary,
                author, library_name, pipeline_tag, context_length, state, bytes_received, entity_tag,
                last_modified, failure_code, retry_count, platform_task_id, lease_owner,
                lease_expires_at_epoch_ms, staging_token, updated_at_epoch_ms
            ) VALUES (
                ?, ?, 'owner/model', '${"a".repeat(40)}', 'weights/model.gguf', '${"b".repeat(64)}',
                1024, 'model', ?, '${"c".repeat(64)}', 1,
                NULL, NULL, NULL, NULL, ?, 0, NULL, NULL, NULL, 0, 'platform-task', 'legacy-owner',
                99, ?, 1
            )
            """.trimIndent(),
        ).use { statement ->
            statement.bindText(1, artifactId)
            statement.bindText(2, batchId)
            statement.bindText(3, destination)
            statement.bindText(4, artifactState)
            statement.bindText(5, artifactId)
            statement.step()
        }
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
