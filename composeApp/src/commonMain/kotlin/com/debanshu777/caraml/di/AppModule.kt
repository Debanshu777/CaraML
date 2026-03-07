package com.debanshu777.caraml.di

import com.debanshu777.caraml.domain.InferenceRepository
import com.debanshu777.caraml.domain.LlamaInferenceRepository
import com.debanshu777.caraml.storage.AppDatabase
import com.debanshu777.caraml.storage.localModel.LocalModelRepository
import com.debanshu777.caraml.ui.viewmodel.ChatViewModel
import com.debanshu777.caraml.ui.viewmodel.DownloadedModelsViewModel
import com.debanshu777.caraml.ui.viewmodel.ModelViewModel
import com.debanshu777.huggingfacemanager.createHuggingFaceApi
import com.debanshu777.huggingfacemanager.download.DownloadManager
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

expect val platformHuggingFaceModule: Module

val appModule = module {
    includes(platformHuggingFaceModule)

    single { get<AppDatabase>().localModelDao() }

    single { LocalModelRepository(get()) }
    single { DownloadManager(get()) }

    single { createHuggingFaceApi() }

    single<InferenceRepository> {
        LlamaInferenceRepository(
            storagePathProvider = get()
        )
    }

    viewModel { 
        ModelViewModel(
            api = get(),
            localModelRepository = get(),
            downloadManager = get()
        ) 
    }
    viewModel { 
        DownloadedModelsViewModel(
            localModelRepository = get()
        ) 
    }
    viewModel {
        ChatViewModel(
            localModelRepository = get(),
            inferenceRepository = get()
        )
    }
}
