package com.debanshu777.caraml.core.recommendation.storage

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File

fun getRecommendationDatabaseBuilder(
    context: Context,
    dbPath: String,
): RoomDatabase.Builder<RecommendationDatabase> = Room.databaseBuilder(
    context = context.applicationContext,
    name = dbPath,
)

internal actual fun deleteRecommendationDatabaseFiles(dbPath: String): Boolean {
    if (!isSafeRecommendationDatabasePath(dbPath)) return false
    return listOf(dbPath, "$dbPath-wal", "$dbPath-shm").all { path ->
        val file = File(path)
        !file.exists() || file.isFile && file.delete()
    }
}
