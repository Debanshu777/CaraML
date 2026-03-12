package com.debanshu777.caraml.core.di

import com.debanshu777.caraml.core.domain.InferenceRepository
import com.debanshu777.caraml.core.domain.LlamaInferenceRepository
import com.debanshu777.caraml.core.storage.AppDatabase
import com.debanshu777.caraml.core.storage.localmodel.LocalModelRepository
import com.debanshu777.caraml.features.chat.domain.ChatConfig
import com.debanshu777.caraml.features.chat.domain.usecase.GenerateResponseUseCase
import com.debanshu777.caraml.features.chat.domain.usecase.GetAvailableModelsUseCase
import com.debanshu777.caraml.features.chat.domain.usecase.ManageContextUseCase
import com.debanshu777.caraml.features.chat.domain.usecase.TrackModelUsageUseCase
import com.debanshu777.caraml.features.chat.presentation.ChatViewModel
import com.debanshu777.caraml.features.modelhub.presentation.downloaded.DownloadedModelsViewModel
import com.debanshu777.caraml.features.modelhub.presentation.search.ModelViewModel
import com.debanshu777.huggingfacemanager.createHuggingFaceApi
import com.debanshu777.huggingfacemanager.download.DownloadManager
import com.debanshu777.runner.LlamaRunner
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

    single { LlamaRunner() }

    single<InferenceRepository> {
        LlamaInferenceRepository(
            storagePathProvider = get(),
            runner = get()
        )
    }

    single { ChatConfig() }

    factory { GetAvailableModelsUseCase(get(), get()) }
    factory { GenerateResponseUseCase(get()) }
    factory { ManageContextUseCase(get(), get()) }
    factory { TrackModelUsageUseCase(get()) }

    viewModel { 
        ModelViewModel(
            api = get(),
            localModelRepository = get(),
            downloadManager = get(),
            storagePathProvider = get()
        ) 
    }
    viewModel { 
        DownloadedModelsViewModel(
            localModelRepository = get()
        ) 
    }
    viewModel {
        ChatViewModel(
            getAvailableModels = get(),
            generateResponse = get(),
            manageContext = get(),
            trackModelUsage = get(),
            inferenceRepository = get()
        )
    }
}
