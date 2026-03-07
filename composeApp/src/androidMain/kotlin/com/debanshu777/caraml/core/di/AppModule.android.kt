package com.debanshu777.caraml.core.di

import android.content.Context
import com.debanshu777.caraml.core.storage.AppDatabase
import com.debanshu777.caraml.core.storage.getDatabaseBuilder
import com.debanshu777.caraml.core.storage.getRoomDatabase
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
