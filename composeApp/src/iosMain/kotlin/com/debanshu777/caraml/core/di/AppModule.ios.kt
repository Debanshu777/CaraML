package com.debanshu777.caraml.core.di

import com.debanshu777.caraml.core.storage.AppDatabase
import com.debanshu777.caraml.core.storage.getDatabaseBuilder
import com.debanshu777.caraml.core.storage.getRoomDatabase
import com.debanshu777.caraml.core.recommendation.storage.RecommendationDatabaseOwner
import com.debanshu777.caraml.core.recommendation.storage.getRecommendationDatabaseBuilder
import com.debanshu777.caraml.core.recommendation.storage.getRecommendationRoomDatabase
import com.debanshu777.caraml.core.download.DownloadNotificationPermissionController
import com.debanshu777.caraml.core.download.IosDownloadNotificationPermissionController
import com.debanshu777.caraml.core.download.IosDownloadScheduler
import com.debanshu777.caraml.core.download.IosResumeDataStore
import com.debanshu777.caraml.core.download.IosCompletedFileStore
import com.debanshu777.caraml.core.download.PlatformDownloadScheduler
import com.debanshu777.caraml.core.download.storage.DownloadDatabase
import com.debanshu777.caraml.core.download.storage.getDownloadDatabaseBuilder
import com.debanshu777.caraml.core.download.storage.getDownloadRoomDatabase
import com.debanshu777.huggingfacemanager.download.StoragePathProvider
import com.debanshu777.huggingfacemanager.download.IosStoragePathProvider
import com.debanshu777.huggingfacemanager.download.IosCompletedDownloadImporter
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

    single<DownloadDatabase> {
        getDownloadRoomDatabase(getDownloadDatabaseBuilder(get<StoragePathProvider>().getDownloadDatabasePath()))
    }
    single { IosCompletedDownloadImporter(get()) }
    single {
        val databasePath = get<StoragePathProvider>().getDownloadDatabasePath()
        IosResumeDataStore("${databasePath.substringBeforeLast('/')}/download-resume")
    }
    single {
        val databasePath = get<StoragePathProvider>().getDownloadDatabasePath()
        IosCompletedFileStore("${databasePath.substringBeforeLast('/')}/download-native-completed")
    }
    single<PlatformDownloadScheduler> {
        IosDownloadScheduler(get(), get(), get(), get(), get(), get<DownloadRuntimeScope>().scope)
    }
    single<DownloadNotificationPermissionController> { IosDownloadNotificationPermissionController }

    single {
        val dbPath = get<StoragePathProvider>().getRecommendationDatabasePath()
        RecommendationDatabaseOwner(dbPath) {
            getRecommendationRoomDatabase(getRecommendationDatabaseBuilder(dbPath))
        }
    }
}

@OptIn(ExperimentalNativeApi::class)
internal actual fun platformIsDebugBuild(): Boolean = Platform.isDebugBinary
