package com.debanshu777.caraml.core.recommendation.storage

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase

fun getRecommendationDatabaseBuilder(
    context: Context,
    dbPath: String,
): RoomDatabase.Builder<RecommendationDatabase> = Room.databaseBuilder(
    context = context.applicationContext,
    name = dbPath,
)
