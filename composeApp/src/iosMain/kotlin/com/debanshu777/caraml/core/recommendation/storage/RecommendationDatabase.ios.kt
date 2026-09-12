package com.debanshu777.caraml.core.recommendation.storage

import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager

fun getRecommendationDatabaseBuilder(
    dbPath: String,
): RoomDatabase.Builder<RecommendationDatabase> = Room.databaseBuilder(name = dbPath)

@OptIn(ExperimentalForeignApi::class)
internal actual fun deleteRecommendationDatabaseFiles(dbPath: String): Boolean {
    if (!isSafeRecommendationDatabasePath(dbPath)) return false
    val manager = NSFileManager.defaultManager
    return listOf(dbPath, "$dbPath-wal", "$dbPath-shm").all { path ->
        !manager.fileExistsAtPath(path) || manager.removeItemAtPath(path, error = null)
    }
}
