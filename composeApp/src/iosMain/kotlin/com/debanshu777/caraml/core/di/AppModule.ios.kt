package com.debanshu777.caraml.core.di

import com.debanshu777.caraml.core.storage.AppDatabase
import com.debanshu777.caraml.core.storage.getDatabaseBuilder
import com.debanshu777.caraml.core.storage.getRoomDatabase
import com.debanshu777.huggingfacemanager.download.StoragePathProvider
import com.debanshu777.huggingfacemanager.download.IosStoragePathProvider
import org.koin.dsl.module
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform

actual val platformHuggingFaceModule = module {
    single<StoragePathProvider> { IosStoragePathProvider() }

    single<AppDatabase> {
        val pathProvider = get<StoragePathProvider>()
        val dbPath = pathProvider.getDatabasePath()
        val builder = getDatabaseBuilder(dbPath)
        getRoomDatabase(builder)
    }
}

@OptIn(ExperimentalNativeApi::class)
internal actual fun platformIsDebugBuild(): Boolean = Platform.isDebugBinary
