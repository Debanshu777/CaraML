package com.debanshu777.caraml.core.download.storage

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase

fun getDownloadDatabaseBuilder(
    context: Context,
    dbPath: String,
): RoomDatabase.Builder<DownloadDatabase> = Room.databaseBuilder(
    context = context.applicationContext,
    name = dbPath,
)
