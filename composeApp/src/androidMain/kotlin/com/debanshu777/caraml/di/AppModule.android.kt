package com.debanshu777.caraml.di

import android.content.Context
import com.debanshu777.caraml.storage.AppDatabase
import com.debanshu777.caraml.storage.getDatabaseBuilder
import com.debanshu777.caraml.storage.getRoomDatabase
import com.debanshu777.huggingfacemanager.download.AndroidStoragePathProvider
import com.debanshu777.huggingfacemanager.download.StoragePathProvider
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.mp.KoinPlatform

actual val platformHuggingFaceModule: Module = module {
    // Storage path provider (Android-specific)
    single<StoragePathProvider> { AndroidStoragePathProvider(KoinPlatform.getKoin().get<Context>()) }
    
    // Database (Android-specific)
    single<AppDatabase> { 
        val pathProvider = get<StoragePathProvider>()
        val dbPath = pathProvider.getDatabasePath()
        val builder = getDatabaseBuilder(KoinPlatform.getKoin().get<Context>(), dbPath)
        getRoomDatabase(builder)
    }
}
