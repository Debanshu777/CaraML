package com.debanshu777.caraml.core.download.storage

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL

@Database(
    entities = [DownloadBatchEntity::class, DownloadArtifactEntity::class],
    version = 3,
    exportSchema = false,
)
@ConstructedBy(DownloadDatabaseConstructor::class)
abstract class DownloadDatabase : RoomDatabase() {
    abstract fun downloadTaskDao(): DownloadTaskDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object DownloadDatabaseConstructor : RoomDatabaseConstructor<DownloadDatabase> {
    override fun initialize(): DownloadDatabase
}

fun getDownloadRoomDatabase(
    builder: RoomDatabase.Builder<DownloadDatabase>,
): DownloadDatabase = builder
    .setDriver(BundledSQLiteDriver())
    .addMigrations(DOWNLOAD_MIGRATION_1_2, DOWNLOAD_MIGRATION_2_3)
    .build()

val DOWNLOAD_MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE download_batch ADD COLUMN evidence_state TEXT")
        connection.execSQL("ALTER TABLE download_batch ADD COLUMN evidence_schema_version INTEGER")
        connection.execSQL("ALTER TABLE download_batch ADD COLUMN evidence_payload TEXT")
        connection.execSQL("ALTER TABLE download_batch ADD COLUMN evidence_sha256 TEXT")
    }
}

/**
 * H1 made artifact destinations immutable-generation scoped. Work persisted by an older binary must
 * never be resumed because its destination could alias another revision's bytes. Completed rows are
 * retained for exact manifest-backed, read-only discovery; every mutable legacy row is quarantined.
 */
val DOWNLOAD_MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(connection: SQLiteConnection) {
        val immutableLayout = """
            length(bundle_id) = 64
            AND bundle_id = lower(bundle_id)
            AND bundle_id NOT GLOB '*[^0-9a-f]*'
            AND destination_relative_path LIKE '.caraml-artifacts/' || bundle_id || '/%'
            AND length(destination_relative_path) > length('.caraml-artifacts/' || bundle_id || '/')
            AND destination_relative_path NOT LIKE '%//%'
            AND destination_relative_path NOT LIKE '%/./%'
            AND destination_relative_path NOT LIKE '%/../%'
            AND destination_relative_path NOT LIKE '%/.'
            AND destination_relative_path NOT LIKE '%/..'
        """.trimIndent()
        connection.execSQL(
            """
            UPDATE download_artifact
            SET state = 'FAILED_TERMINAL', failure_code = 'SECURE_PATH',
                platform_task_id = NULL, lease_owner = NULL, lease_expires_at_epoch_ms = NULL
            WHERE state NOT IN ('COMPLETED', 'FAILED_TERMINAL', 'CANCELLED')
              AND NOT ($immutableLayout)
            """.trimIndent(),
        )
        connection.execSQL(
            """
            UPDATE download_batch
            SET state = 'FAILED_TERMINAL', failure_code = 'SECURE_PATH'
            WHERE state NOT IN ('COMPLETED', 'FAILED_TERMINAL', 'CANCELLED')
              AND EXISTS (
                  SELECT 1 FROM download_artifact
                  WHERE download_artifact.batch_id = download_batch.batch_id
                    AND download_artifact.state = 'FAILED_TERMINAL'
                    AND download_artifact.failure_code = 'SECURE_PATH'
              )
            """.trimIndent(),
        )
    }
}
