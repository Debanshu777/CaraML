package com.debanshu777.caraml.core.storage

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.debanshu777.caraml.core.storage.catalog.InstalledModelCatalogDao
import com.debanshu777.caraml.core.storage.component.DownloadedComponentDao
import com.debanshu777.caraml.core.storage.component.DownloadedComponentEntity
import com.debanshu777.caraml.core.storage.component.ModelComponentLinkEntity
import com.debanshu777.caraml.core.storage.evidence.InstalledModelEvidenceDao
import com.debanshu777.caraml.core.storage.evidence.InstalledModelEvidenceEntity
import com.debanshu777.caraml.core.storage.localmodel.LocalModelDao
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity

@Database(
    entities = [
        LocalModelEntity::class,
        DownloadedComponentEntity::class,
        ModelComponentLinkEntity::class,
        InstalledModelEvidenceEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun localModelDao(): LocalModelDao
    abstract fun downloadedComponentDao(): DownloadedComponentDao
    abstract fun installedModelEvidenceDao(): InstalledModelEvidenceDao
    abstract fun installedModelCatalogDao(): InstalledModelCatalogDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}

fun getRoomDatabase(builder: RoomDatabase.Builder<AppDatabase>): AppDatabase {
    return builder
        .setDriver(BundledSQLiteDriver())
        .addMigrations(APP_MIGRATION_3_4, APP_MIGRATION_4_5, APP_MIGRATION_5_4)
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()
}

val APP_MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS installed_model_evidence (
                model_id TEXT NOT NULL PRIMARY KEY,
                evidence_state TEXT NOT NULL,
                schema_version INTEGER NOT NULL,
                payload TEXT NOT NULL,
                sha256 TEXT NOT NULL,
                published_at_epoch_ms INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }
}

val APP_MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE downloaded_component ADD COLUMN immutable_revision TEXT")
        connection.execSQL("ALTER TABLE downloaded_component ADD COLUMN remote_object_id TEXT")
        connection.execSQL("ALTER TABLE downloaded_component ADD COLUMN bundle_id TEXT")
        connection.execSQL("ALTER TABLE downloaded_component ADD COLUMN content_sha256 TEXT")
        connection.execSQL("DROP INDEX IF EXISTS index_downloaded_component_repo_id_file_path")
        connection.execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS index_downloaded_component_exact_storage_identity
            ON downloaded_component (
                repo_id, immutable_revision, file_path, remote_object_id, bundle_id, local_path
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS index_downloaded_component_local_path ON downloaded_component (local_path)",
        )
    }
}

/** Explicit, lossless downgrade for schemas whose exact rows still satisfy v4 repo/path uniqueness. */
val APP_MIGRATION_5_4 = object : Migration(5, 4) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE downloaded_component_v4 (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                repo_id TEXT NOT NULL,
                file_path TEXT NOT NULL,
                role TEXT NOT NULL,
                local_path TEXT NOT NULL,
                size_bytes INTEGER,
                downloaded_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT INTO downloaded_component_v4 (
                id, repo_id, file_path, role, local_path, size_bytes, downloaded_at
            )
            SELECT id, repo_id, file_path, role, local_path, size_bytes, downloaded_at
            FROM downloaded_component
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE TABLE model_component_link_backup (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                model_id TEXT NOT NULL,
                component_id INTEGER NOT NULL,
                role TEXT NOT NULL
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT INTO model_component_link_backup (id, model_id, component_id, role)
            SELECT id, model_id, component_id, role FROM model_component_link
            """.trimIndent(),
        )
        connection.execSQL("DROP TABLE model_component_link")
        connection.execSQL("DROP TABLE downloaded_component")
        connection.execSQL("ALTER TABLE downloaded_component_v4 RENAME TO downloaded_component")
        connection.execSQL(
            """
            CREATE TABLE model_component_link (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                model_id TEXT NOT NULL,
                component_id INTEGER NOT NULL,
                role TEXT NOT NULL,
                FOREIGN KEY(component_id) REFERENCES downloaded_component(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT INTO model_component_link (id, model_id, component_id, role)
            SELECT id, model_id, component_id, role FROM model_component_link_backup
            """.trimIndent(),
        )
        connection.execSQL("DROP TABLE model_component_link_backup")
        connection.execSQL(
            "CREATE UNIQUE INDEX index_downloaded_component_repo_id_file_path ON downloaded_component (repo_id, file_path)",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX index_model_component_link_model_id_component_id ON model_component_link (model_id, component_id)",
        )
        connection.execSQL(
            "CREATE INDEX index_model_component_link_component_id ON model_component_link (component_id)",
        )
    }
}
