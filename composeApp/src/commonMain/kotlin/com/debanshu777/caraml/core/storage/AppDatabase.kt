package com.debanshu777.caraml.core.storage

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.debanshu777.caraml.core.storage.component.DownloadedComponentDao
import com.debanshu777.caraml.core.storage.component.DownloadedComponentEntity
import com.debanshu777.caraml.core.storage.component.ModelComponentLinkEntity
import com.debanshu777.caraml.core.storage.localmodel.LocalModelDao
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity

@Database(
    entities = [
        LocalModelEntity::class,
        DownloadedComponentEntity::class,
        ModelComponentLinkEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun localModelDao(): LocalModelDao
    abstract fun downloadedComponentDao(): DownloadedComponentDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE local_model ADD COLUMN arch TEXT")
    }
}

fun getRoomDatabase(builder: RoomDatabase.Builder<AppDatabase>): AppDatabase {
    return builder
        .setDriver(BundledSQLiteDriver())
        .addMigrations(MIGRATION_2_3)
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()
}
