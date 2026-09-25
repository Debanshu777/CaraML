package com.debanshu777.caraml.core.recommendation.storage

import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File

fun getRecommendationDatabaseBuilder(
    dbPath: String,
): RoomDatabase.Builder<RecommendationDatabase> = Room.databaseBuilder(name = dbPath)

internal actual fun deleteRecommendationDatabaseFiles(dbPath: String): Boolean {
    if (!isSafeRecommendationDatabasePath(dbPath)) return false
    return listOf(dbPath, "$dbPath-wal", "$dbPath-shm").all { path ->
        val file = File(path)
        !file.exists() || file.isFile && file.delete()
    }
}
