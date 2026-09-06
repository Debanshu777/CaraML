package com.debanshu777.caraml.core.di

import com.debanshu777.caraml.core.storage.AppDatabase
import com.debanshu777.caraml.core.storage.getDatabaseBuilder
import com.debanshu777.caraml.core.storage.getRoomDatabase
import com.debanshu777.huggingfacemanager.download.StoragePathProvider
import com.debanshu777.huggingfacemanager.download.JvmStoragePathProvider
import org.koin.dsl.module

actual val platformHuggingFaceModule = module {
    single<StoragePathProvider> { JvmStoragePathProvider() }

    single<AppDatabase> {
        val pathProvider = get<StoragePathProvider>()
        val dbPath = pathProvider.getDatabasePath()
        val builder = getDatabaseBuilder(dbPath)
        getRoomDatabase(builder)
    }
}

internal actual fun platformIsDebugBuild(): Boolean =
    System.getProperty(RECOMMENDATION_DEBUG_PROPERTY)?.toBooleanStrictOrNull() == true

private const val RECOMMENDATION_DEBUG_PROPERTY = "caraml.recommendation.debugBuild"
