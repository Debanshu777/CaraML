package com.debanshu777.caraml.core.download.storage

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.debanshu777.caraml.core.download.transfer.DownloadArtifactIdentity
import com.debanshu777.caraml.core.download.transfer.persistedArtifactStorageLocation

/** Existing cancelled tasks may have stopped before checkpoint cleanup finished. */
internal val DOWNLOAD_MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE download_batch ADD COLUMN cancellation_cleanup_pending INTEGER NOT NULL DEFAULT 0")
        connection.execSQL("UPDATE download_batch SET cancellation_cleanup_pending = 1 WHERE user_intent = 'CANCEL'")
    }
}

/** Version 2 added model evidence; the original queue identity and checkpoints stay intact. */
internal val DOWNLOAD_MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE download_batch ADD COLUMN evidence_state TEXT")
        connection.execSQL("ALTER TABLE download_batch ADD COLUMN evidence_schema_version INTEGER")
        connection.execSQL("ALTER TABLE download_batch ADD COLUMN evidence_payload TEXT")
        connection.execSQL("ALTER TABLE download_batch ADD COLUMN evidence_sha256 TEXT")
    }
}

/**
 * Version 3 requires immutable destinations and persisted evidence. Preserve historical records and
 * byte checkpoints, but quarantine whole mutable batches whose old paths cannot safely be resumed.
 * Never rewrite destinations, manufacture evidence, or change task IDs to make legacy work runnable.
 * The task store still validates complete batch identity before any recovered work can be claimed.
 */
internal val DOWNLOAD_MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(connection: SQLiteConnection) {
        val unsafeBatches = mutableSetOf<String>()
        connection.prepare(
            """
            SELECT batch_id FROM download_batch
            WHERE evidence_state IS NULL OR evidence_schema_version IS NULL
                OR evidence_payload IS NULL OR evidence_sha256 IS NULL
                OR NOT EXISTS (SELECT 1 FROM download_artifact WHERE download_artifact.batch_id = download_batch.batch_id)
            """.trimIndent(),
        ).use { statement ->
            while (statement.step()) unsafeBatches += statement.getText(0)
        }
        connection.prepare(
            """
            SELECT batch_id, repository_id, immutable_revision, relative_path, remote_object_id,
                expected_bytes, bundle_id, destination_relative_path
            FROM download_artifact
            """.trimIndent(),
        ).use { statement ->
            while (statement.step()) {
                val identity = DownloadArtifactIdentity.create(
                    repositoryId = statement.getText(1),
                    immutableRevision = statement.getText(2),
                    relativePath = statement.getText(3),
                    remoteObjectId = if (statement.isNull(4)) null else statement.getText(4),
                    expectedBytes = statement.getLong(5),
                )
                if (identity == null || persistedArtifactStorageLocation(
                        identity, statement.getText(6), statement.getText(7),
                    ) == null
                ) {
                    unsafeBatches += statement.getText(0)
                }
            }
        }
        unsafeBatches.forEach { batchId ->
            connection.prepare(
                """
                UPDATE download_artifact
                SET state = 'FAILED_TERMINAL', failure_code = 'SECURE_PATH',
                    platform_task_id = NULL, lease_owner = NULL, lease_expires_at_epoch_ms = NULL
                WHERE batch_id = ? AND state NOT IN ('COMPLETED', 'FAILED_TERMINAL', 'CANCELLED')
                """.trimIndent(),
            ).use { statement ->
                statement.bindText(1, batchId)
                statement.step()
            }
            connection.prepare(
                """
                UPDATE download_batch SET state = 'FAILED_TERMINAL', failure_code = 'SECURE_PATH'
                WHERE batch_id = ? AND state NOT IN ('COMPLETED', 'FAILED_TERMINAL', 'CANCELLED')
                """.trimIndent(),
            ).use { statement ->
                statement.bindText(1, batchId)
                statement.step()
            }
        }
    }
}
