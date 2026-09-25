package com.debanshu777.caraml.core.recommendation.storage

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

@Database(
    entities = [RecommendationObservationEntity::class],
    version = 1,
    exportSchema = false,
)
@ConstructedBy(RecommendationDatabaseConstructor::class)
abstract class RecommendationDatabase : RoomDatabase() {
    abstract fun observationDao(): RecommendationObservationDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object RecommendationDatabaseConstructor : RoomDatabaseConstructor<RecommendationDatabase> {
    override fun initialize(): RecommendationDatabase
}

fun getRecommendationRoomDatabase(
    builder: RoomDatabase.Builder<RecommendationDatabase>,
): RecommendationDatabase = builder
    .setDriver(BundledSQLiteDriver())
    .fallbackToDestructiveMigration(dropAllTables = true)
    .build()

/** Owns only the disposable recommendation cache and can replace it after a corrupt open. */
class RecommendationDatabaseOwner(
    private val dbPath: String,
    private val openDatabase: () -> RecommendationDatabase,
) {
    private var database: RecommendationDatabase = openDatabase()

    fun observationDao(): RecommendationObservationDao = database.observationDao()

    fun recoverObservationDao(): RecommendationObservationDao? {
        database.close()
        if (!deleteRecommendationDatabaseFiles(dbPath)) return null
        val reopened = try {
            openDatabase()
        } catch (_: Exception) {
            return null
        }
        database = reopened
        return reopened.observationDao()
    }

    fun close() = database.close()
}

internal fun isSafeRecommendationDatabasePath(path: String): Boolean {
    if (path.isBlank() || path.length > 4_096 || '\u0000' in path) return false
    val separator = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
    return path.substring(separator + 1) == RECOMMENDATION_DATABASE_FILENAME
}

internal const val RECOMMENDATION_DATABASE_FILENAME = "recommendation_cache.db"

internal expect fun deleteRecommendationDatabaseFiles(dbPath: String): Boolean
