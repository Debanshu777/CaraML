package com.debanshu777.caraml.core.recommendation.storage

import androidx.room.Room
import androidx.room.RoomDatabase

fun getRecommendationDatabaseBuilder(
    dbPath: String,
): RoomDatabase.Builder<RecommendationDatabase> = Room.databaseBuilder(name = dbPath)
