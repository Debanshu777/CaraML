package com.debanshu777.caraml.core.di

import com.debanshu777.caraml.core.data.inference.DiffusionInferenceRepository
import com.debanshu777.caraml.core.data.inference.InferenceRepository
import com.debanshu777.caraml.core.data.inference.LlamaInferenceRepository
import com.debanshu777.caraml.core.recommendation.CalibrationSource
import com.debanshu777.caraml.core.recommendation.CompatibilityChecker
import com.debanshu777.caraml.core.recommendation.DefaultRecommendationRolloutModeSource
import com.debanshu777.caraml.core.recommendation.DeviceSnapshotProvider
import com.debanshu777.caraml.core.recommendation.EngineCapabilitySource
import com.debanshu777.caraml.core.recommendation.LegacySuitabilityAdapter
import com.debanshu777.caraml.core.recommendation.ModelAssessmentRepository
import com.debanshu777.caraml.core.recommendation.ModelDescriptorFactory
import com.debanshu777.caraml.core.recommendation.NoCalibrationSource
import com.debanshu777.caraml.core.recommendation.RecommendationPolicy
import com.debanshu777.caraml.core.recommendation.RecommendationRolloutModeSource
import com.debanshu777.caraml.core.recommendation.SuitabilityEngine
import com.debanshu777.caraml.core.recommendation.UnknownEngineCapabilitySource
import com.debanshu777.diffusionrunner.DiffusionRunner
import com.debanshu777.caraml.core.platform.BackendCapabilitySource
import com.debanshu777.caraml.core.platform.DeviceCapabilities
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
import com.debanshu777.caraml.features.modelhub.domain.HuggingFaceModelMetadataSource
import com.debanshu777.caraml.features.modelhub.domain.ModelMetadataSource
import com.debanshu777.caraml.features.modelhub.domain.ModelRecommendationService
import com.debanshu777.caraml.features.modelhub.presentation.search.ModelViewModel
import com.debanshu777.caraml.features.settings.presentation.SettingsViewModel
import com.debanshu777.huggingfacemanager.createHuggingFaceApi
import com.debanshu777.huggingfacemanager.download.DownloadManager
import com.debanshu777.runner.LlamaRunner
import kotlinx.coroutines.Dispatchers
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import kotlin.time.Clock

expect val platformHuggingFaceModule: Module
internal expect fun platformIsDebugBuild(): Boolean

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

    single { DeviceCapabilities() }
    single<BackendCapabilitySource> { BackendCapabilitySource { emptyList() } }
    single<EngineCapabilitySource> { UnknownEngineCapabilitySource }
    single<CalibrationSource> { NoCalibrationSource }
    single { CompatibilityChecker(get()) }
    single { SuitabilityEngine(get(), get()) }
    single { RecommendationPolicy() }
    single { ModelAssessmentRepository(get(), get(), get()) }
    single { ModelDescriptorFactory() }
    single {
        DeviceSnapshotProvider(
            capabilities = get(),
            backendCapabilitySource = get(),
            storage = get(),
            probeDispatcher = Dispatchers.Default,
            clock = { Clock.System.now().toEpochMilliseconds() },
        )
    }
    single<ModelMetadataSource> { HuggingFaceModelMetadataSource(get(), get()) }
    single {
        ModelRecommendationService(
            metadataSource = get(),
            assessmentRepository = get(),
            suitabilityEngine = get(),
            recommendationPolicy = get(),
            snapshotProvider = get(),
        )
    }
    single<RecommendationRolloutModeSource> {
        DefaultRecommendationRolloutModeSource(isDebugBuild = platformIsDebugBuild())
    }
    single { LegacySuitabilityAdapter(get()) }

    single { LlamaRunner() }
    single { DiffusionRunner() }

    single {
        DiffusionInferenceRepository(
            storagePathProvider = get(),
            runner = get(),
            deviceCapabilities = get(),
            settingsRepository = get(),
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
            storagePathProvider = get(),
            deviceCapabilities = get(),
            recommendationService = get(),
            settingsRepository = get(),
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
