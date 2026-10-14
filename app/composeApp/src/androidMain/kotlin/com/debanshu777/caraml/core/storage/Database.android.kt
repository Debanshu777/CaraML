package com.debanshu777.caraml.core.storage

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase

fun getDatabaseBuilder(context: Context, dbPath: String): RoomDatabase.Builder<AppDatabase> {
    return Room.databaseBuilder<AppDatabase>(
        context = context.applicationContext,
        name = dbPath
    )
}
