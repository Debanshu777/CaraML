package com.debanshu777.caraml.core.download.storage

import androidx.room.Room
import androidx.room.RoomDatabase

fun getDownloadDatabaseBuilder(
    dbPath: String,
): RoomDatabase.Builder<DownloadDatabase> = Room.databaseBuilder(name = dbPath)
