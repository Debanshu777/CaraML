package com.debanshu777.caraml.core.di

import android.content.Context
import android.content.pm.ApplicationInfo
import com.debanshu777.caraml.core.storage.AppDatabase
import com.debanshu777.caraml.core.storage.getDatabaseBuilder
import com.debanshu777.caraml.core.storage.getRoomDatabase
import com.debanshu777.caraml.core.recommendation.storage.RecommendationDatabaseOwner
import com.debanshu777.caraml.core.recommendation.storage.getRecommendationDatabaseBuilder
import com.debanshu777.caraml.core.recommendation.storage.getRecommendationRoomDatabase
import com.debanshu777.caraml.core.download.AndroidDownloadNotificationPermissionController
import com.debanshu777.caraml.core.download.AndroidDownloadScheduler
import com.debanshu777.caraml.core.download.DownloadNotificationPermissionController
import com.debanshu777.caraml.core.download.DownloadTaskStore
import com.debanshu777.caraml.core.download.PlatformDownloadScheduler
import com.debanshu777.caraml.core.download.storage.DownloadDatabase
import com.debanshu777.caraml.core.download.storage.getDownloadDatabaseBuilder
import com.debanshu777.caraml.core.download.storage.getDownloadRoomDatabase
import com.debanshu777.huggingfacemanager.download.AndroidStoragePathProvider
import com.debanshu777.huggingfacemanager.download.StoragePathProvider
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.mp.KoinPlatform

actual val platformHuggingFaceModule: Module = module {
    single<StoragePathProvider> { AndroidStoragePathProvider(KoinPlatform.getKoin().get<Context>()) }

    single<AppDatabase> {
        val pathProvider = get<StoragePathProvider>()
        val dbPath = pathProvider.getDatabasePath()
        val builder = getDatabaseBuilder(KoinPlatform.getKoin().get<Context>(), dbPath)
        getRoomDatabase(builder)
    }

    single<DownloadDatabase> {
        val context = KoinPlatform.getKoin().get<Context>()
        getDownloadRoomDatabase(
            getDownloadDatabaseBuilder(context, get<StoragePathProvider>().getDownloadDatabasePath()),
        )
    }
    single { AndroidDownloadScheduler(get<Context>(), get<DownloadTaskStore>()) }
    single<PlatformDownloadScheduler> { get<AndroidDownloadScheduler>() }
    single { AndroidDownloadNotificationPermissionController() }
    single<DownloadNotificationPermissionController> { get<AndroidDownloadNotificationPermissionController>() }

    single {
        val context = KoinPlatform.getKoin().get<Context>()
        val dbPath = get<StoragePathProvider>().getRecommendationDatabasePath()
        RecommendationDatabaseOwner(dbPath) {
            getRecommendationRoomDatabase(getRecommendationDatabaseBuilder(context, dbPath))
        }
    }
}

internal actual fun platformIsDebugBuild(): Boolean {
    val applicationInfo = KoinPlatform.getKoin().get<Context>().applicationInfo
    return applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
}
