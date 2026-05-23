package com.debanshu777.caraml.core.di

import com.debanshu777.caraml.core.data.Inference.DiffusionInferenceRepository
import com.debanshu777.caraml.core.data.Inference.InferenceRepository
import com.debanshu777.caraml.core.data.Inference.LlamaInferenceRepository
import com.debanshu777.diffusionrunner.DiffusionRunner
import com.debanshu777.caraml.core.storage.AppDatabase
import com.debanshu777.caraml.core.storage.component.ComponentRepository
import com.debanshu777.caraml.core.storage.localmodel.LocalModelRepository
import com.debanshu777.caraml.core.data.settings.DefaultSettingsRepository
import com.debanshu777.caraml.core.data.settings.SettingsRepository
import com.debanshu777.caraml.core.data.theme.DefaultThemeRepository
import com.debanshu777.caraml.core.data.theme.ThemeRepository
import com.debanshu777.caraml.core.settings.createPreferencesDataStore
import com.debanshu777.caraml.core.theme.ThemeViewModel
import com.debanshu777.caraml.features.chat.domain.ChatConfig
import com.debanshu777.caraml.features.chat.domain.usecase.GenerateResponseUseCase
import com.debanshu777.caraml.features.chat.domain.usecase.GetAvailableModelsUseCase
import com.debanshu777.caraml.features.chat.domain.usecase.ManageContextUseCase
import com.debanshu777.caraml.features.chat.domain.usecase.TrackModelUsageUseCase
import com.debanshu777.caraml.features.chat.presentation.ChatViewModel
import com.debanshu777.caraml.features.modelhub.presentation.downloaded.DownloadedModelsViewModel
import com.debanshu777.caraml.features.modelhub.presentation.search.ModelViewModel
import com.debanshu777.caraml.features.settings.presentation.SettingsViewModel
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
    single { get<AppDatabase>().downloadedComponentDao() }

    single { LocalModelRepository(get()) }
    single { ComponentRepository(get()) }
    single { DownloadManager(get()) }

    single { createHuggingFaceApi() }

    single { createPreferencesDataStore() }
    single<SettingsRepository> { DefaultSettingsRepository(get()) }
    single<ThemeRepository> { DefaultThemeRepository(get()) }

    single { LlamaRunner() }
    single { DiffusionRunner() }

    single {
        DiffusionInferenceRepository(
            storagePathProvider = get(),
            runner = get(),
            deviceCapabilities = get(),
        )
    }

    single<InferenceRepository> {
        LlamaInferenceRepository(
            storagePathProvider = get(),
            runner = get(),
            deviceCapabilities = get(),
            settingsRepository = get(),
            localModelRepository = get(),
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
            componentRepository = get(),
            downloadManager = get(),
            storagePathProvider = get()
        )
    }
    viewModel {
        DownloadedModelsViewModel(
            localModelRepository = get(),
            storagePathProvider = get()
        )
    }
    viewModel {
        SettingsViewModel(
            repository = get()
        )
    }
    viewModel {
        ThemeViewModel(
            repository = get()
        )
    }
    viewModel {
        ChatViewModel(
            getAvailableModels = get(),
            generateResponse = get(),
            manageContext = get(),
            trackModelUsage = get(),
            inferenceRepository = get(),
            diffusionRepository = get(),
            storagePathProvider = get(),
        )
    }
}
