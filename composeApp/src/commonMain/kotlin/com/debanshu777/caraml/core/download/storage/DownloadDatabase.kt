package com.debanshu777.caraml.core.download.storage

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

@Database(
    entities = [DownloadBatchEntity::class, DownloadArtifactEntity::class],
    version = 1,
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
    .build()
