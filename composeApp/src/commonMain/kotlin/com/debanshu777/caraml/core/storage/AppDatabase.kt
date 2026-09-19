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
    version = 4,
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
        .addMigrations(APP_MIGRATION_3_4)
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
