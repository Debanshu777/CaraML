package com.debanshu777.caraml.di

import com.debanshu777.caraml.storage.AppDatabase
import com.debanshu777.caraml.storage.getDatabaseBuilder
import com.debanshu777.caraml.storage.getRoomDatabase
import com.debanshu777.huggingfacemanager.download.StoragePathProvider
import com.debanshu777.huggingfacemanager.download.JvmStoragePathProvider
import org.koin.dsl.module

actual val platformHuggingFaceModule = module {
    // Storage path provider (JVM-specific)
    single<StoragePathProvider> { JvmStoragePathProvider() }
    
    // Database (JVM-specific)
    single<AppDatabase> {
        val pathProvider = get<StoragePathProvider>()
        val dbPath = pathProvider.getDatabasePath()
        val builder = getDatabaseBuilder(dbPath)
        getRoomDatabase(builder)
    }
}
