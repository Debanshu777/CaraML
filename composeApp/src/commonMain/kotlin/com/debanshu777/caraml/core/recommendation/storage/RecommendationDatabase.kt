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
