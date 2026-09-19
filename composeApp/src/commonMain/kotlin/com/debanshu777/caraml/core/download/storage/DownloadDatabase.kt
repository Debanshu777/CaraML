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
    version = 2,
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
    .addMigrations(DOWNLOAD_MIGRATION_1_2)
    .build()

val DOWNLOAD_MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE download_batch ADD COLUMN evidence_state TEXT")
        connection.execSQL("ALTER TABLE download_batch ADD COLUMN evidence_schema_version INTEGER")
        connection.execSQL("ALTER TABLE download_batch ADD COLUMN evidence_payload TEXT")
        connection.execSQL("ALTER TABLE download_batch ADD COLUMN evidence_sha256 TEXT")
    }
}
