package com.debanshu777.caraml.core.di

import com.debanshu777.caraml.core.data.inference.DiffusionInferenceRepository
import com.debanshu777.caraml.core.data.inference.InferenceRepository
import com.debanshu777.caraml.core.data.inference.LlamaInferenceRepository
import com.debanshu777.caraml.core.media.GeneratedMediaStore
import com.debanshu777.caraml.core.recommendation.CalibrationSource
import com.debanshu777.caraml.core.recommendation.BackendCalibrationProbe
import com.debanshu777.caraml.core.recommendation.CalibrationRepository
import com.debanshu777.caraml.core.recommendation.CompatibilityChecker
import com.debanshu777.caraml.core.recommendation.DefaultRecommendationRolloutModeSource
import com.debanshu777.caraml.core.recommendation.DeviceSnapshotProvider
import com.debanshu777.caraml.core.recommendation.EngineCapabilitySource
import com.debanshu777.caraml.core.recommendation.LegacySuitabilityAdapter
import com.debanshu777.caraml.core.recommendation.LoadRecoveryRepository
import com.debanshu777.caraml.core.recommendation.LoadSessionCoordinator
import com.debanshu777.caraml.core.recommendation.LocalArtifactIdentityResolver
import com.debanshu777.caraml.core.recommendation.ModelAssessmentRepository
import com.debanshu777.caraml.core.recommendation.ModelDescriptorFactory
import com.debanshu777.caraml.core.recommendation.NATIVE_LOAD_ENGINE_VERSION
import com.debanshu777.caraml.core.recommendation.InferenceObservationRecorder
import com.debanshu777.caraml.core.recommendation.InstalledDescriptorMetadataSource
import com.debanshu777.caraml.core.recommendation.InstalledModelEvidenceRepairer
import com.debanshu777.caraml.core.recommendation.InstalledModelLoadRequestResolver
import com.debanshu777.caraml.core.recommendation.InstalledModelManifestSource
import com.debanshu777.caraml.core.recommendation.InstalledModelWorkloadFactory
import com.debanshu777.caraml.core.recommendation.LlamaBackendCalibrationProbe
import com.debanshu777.caraml.core.recommendation.QuickCalibrationRunner
import com.debanshu777.caraml.core.recommendation.ReliableMemoryReading
import com.debanshu777.caraml.core.recommendation.RecommendationPolicy
import com.debanshu777.caraml.core.recommendation.RecommendationRolloutModeSource
import com.debanshu777.caraml.core.recommendation.RunnerEngineCapabilitySource
import com.debanshu777.caraml.core.recommendation.SuitabilityEngine
import com.debanshu777.diffusionrunner.DiffusionRunner
import com.debanshu777.caraml.core.platform.BackendCapabilitySource
import com.debanshu777.caraml.core.platform.DeviceCapabilities
import com.debanshu777.caraml.core.platform.RunnerBackendCapabilitySource
import com.debanshu777.caraml.core.storage.AppDatabase
import com.debanshu777.caraml.core.recommendation.storage.RecommendationDatabaseOwner
import com.debanshu777.caraml.core.recommendation.storage.PersistedModelEvidenceCodec
import com.debanshu777.caraml.core.storage.component.ComponentRepository
import com.debanshu777.caraml.core.storage.catalog.InstalledModelPublicationCoordinator
import com.debanshu777.caraml.core.storage.catalog.InstalledModelRemovalService
import com.debanshu777.caraml.core.storage.evidence.InstalledModelEvidenceRepository
import com.debanshu777.caraml.core.storage.localmodel.LocalModelRepository
import com.debanshu777.caraml.core.download.ArtifactTransfer
import com.debanshu777.caraml.core.download.BatchFinalizer
import com.debanshu777.caraml.core.download.BundlePublisher
import com.debanshu777.caraml.core.download.DownloadBatchRunner
import com.debanshu777.caraml.core.download.DownloadCoordinator
import com.debanshu777.caraml.core.download.DownloadCheckpointCleaner
import com.debanshu777.caraml.core.download.DownloadManagerCheckpointCleaner
import com.debanshu777.caraml.core.download.DownloadManagerArtifactTransfer
import com.debanshu777.caraml.core.download.DownloadManagerBundlePublisher
import com.debanshu777.caraml.core.download.DownloadReconciler
import com.debanshu777.caraml.core.download.DownloadRuntime
import com.debanshu777.caraml.core.download.DownloadTaskStore
import com.debanshu777.caraml.core.download.ModelCatalogPublisher
import com.debanshu777.caraml.core.download.ModelDownloadFinalizer
import com.debanshu777.caraml.core.download.RepositoryModelCatalogPublisher
import com.debanshu777.caraml.core.download.storage.DownloadDatabase
import com.debanshu777.caraml.core.download.storage.RoomDownloadTaskStore
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
import com.debanshu777.huggingfacemanager.download.ArtifactRootLifetime
import com.debanshu777.huggingfacemanager.download.artifactRootLifetime
import com.debanshu777.runner.LlamaRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.debanshu777.huggingfacemanager.download.ArtifactManifest
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
    single { get<AppDatabase>().installedModelEvidenceDao() }
    single { get<AppDatabase>().installedModelCatalogDao() }
    single { LocalModelRepository(get()) }
    single { ComponentRepository(get()) }
    single { InstalledModelEvidenceRepository(get()) }
    single { DownloadManager(get()) }
    single<ArtifactRootLifetime> { artifactRootLifetime(get()) }
    single { InstalledModelPublicationCoordinator() }
    single { installedModelManifestSource(get<DownloadManager>()::validatedBundle) }
    single {
        val manifestSource = get<InstalledModelManifestSource>()
        InstalledModelRemovalService(
            catalog = get(),
            storagePathProvider = get(),
            manifestSource = { ownerModelId -> manifestSource(ownerModelId) },
            publicationCoordinator = get(),
        )
    }
    single<DownloadTaskStore> { RoomDownloadTaskStore(get<DownloadDatabase>().downloadTaskDao()) }
    single<ArtifactTransfer> { DownloadManagerArtifactTransfer(get()) }
    single<DownloadCheckpointCleaner> { DownloadManagerCheckpointCleaner(get()) }
    single<BundlePublisher> { DownloadManagerBundlePublisher(get()) }
    single<ModelCatalogPublisher> { RepositoryModelCatalogPublisher(get(), get()) }
    single<BatchFinalizer> { ModelDownloadFinalizer(get(), get(), get(), get()) }
    single { DownloadBatchRunner(get(), get(), get(), { Clock.System.now().toEpochMilliseconds() }) }
    single { DownloadRuntimeScope(CoroutineScope(SupervisorJob() + Dispatchers.Default)) }
    single { DownloadReconciler(get(), get(), { Clock.System.now().toEpochMilliseconds() }) }
    single { DownloadRuntime(get(), get(), get<DownloadRuntimeScope>().scope) }
    single { DownloadCoordinator(get(), get(), get(), get(), { Clock.System.now().toEpochMilliseconds() }) }

    single { createHuggingFaceApi() }

    single { createPreferencesDataStore() }
    single<SettingsRepository> { DefaultSettingsRepository(get()) }
    single<ThemeRepository> { DefaultThemeRepository(get()) }
    single {
        LoadRecoveryRepository(
            dataStore = get(),
            engineVersion = NATIVE_LOAD_ENGINE_VERSION,
            clock = { Clock.System.now().toEpochMilliseconds() },
        )
    }
    single { LoadSessionCoordinator(get(), get()) }

    single { DeviceCapabilities() }
    single<BackendCapabilitySource> { RunnerBackendCapabilitySource(get(), get()) }
    single<EngineCapabilitySource> { RunnerEngineCapabilitySource(get(), get()) }
    single { RecommendationCalibrationScope(CoroutineScope(SupervisorJob() + Dispatchers.Default)) }
    single {
        CalibrationRepository(
            dao = get<RecommendationDatabaseOwner>().observationDao(),
            currentEngineVersion = NATIVE_LOAD_ENGINE_VERSION,
            now = { Clock.System.now().toEpochMilliseconds() },
            recoverDao = { get<RecommendationDatabaseOwner>().recoverObservationDao() },
        ).also { repository ->
            get<RecommendationCalibrationScope>().scope.launch { repository.initialize() }
        }
    }
    single<CalibrationSource> { get<CalibrationRepository>() }
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
    single { HuggingFaceModelMetadataSource(get(), get()) }
    single<ModelMetadataSource> { get<HuggingFaceModelMetadataSource>() }
    single<InstalledDescriptorMetadataSource> { get<HuggingFaceModelMetadataSource>() }
    single { PersistedModelEvidenceCodec() }
    single {
        InstalledModelEvidenceRepairer(
            artifactResolver = get(),
            catalog = get(),
            evidenceRepository = get(),
            metadataSource = get(),
            manifestSource = get(),
            publicationCoordinator = get(),
            codec = get(),
            clock = { Clock.System.now().toEpochMilliseconds() },
        )
    }
    single { InstalledModelWorkloadFactory() }
    single {
        InstalledModelLoadRequestResolver(
            componentRepository = get(),
            evidenceRepairer = get(),
            artifactResolver = get(),
            snapshotProvider = get(),
            assessmentRepository = get(),
            settingsRepository = get(),
            workloadFactory = get(),
        )
    }
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
    factory { GeneratedMediaStore() }

    single { LlamaRunner() }
    single { DiffusionRunner() }
    single<BackendCalibrationProbe> {
        LlamaBackendCalibrationProbe(
            runner = get(),
            dispatcher = Dispatchers.Default,
        )
    }
    single {
        QuickCalibrationRunner(
            snapshotSource = { get<DeviceSnapshotProvider>().capture() },
            probe = get(),
            repository = get(),
            settingsRepository = get(),
            engineVersion = NATIVE_LOAD_ENGINE_VERSION,
            clock = { Clock.System.now().toEpochMilliseconds() },
        )
    }
    single {
        InferenceObservationRecorder(
            repository = get(),
            processMemory = {
                get<DeviceCapabilities>().getResourceSnapshot().let { resources ->
                    ReliableMemoryReading(
                        bytes = resources.currentProcessBytes,
                        reliable = resources.confidence.process ==
                            com.debanshu777.caraml.core.recommendation.Confidence.HIGH,
                    )
                }
            },
        )
    }
    single {
        LocalArtifactIdentityResolver(
            storagePathProvider = get(),
            manifestSource = get<InstalledModelManifestSource>()::invoke,
            hashingDispatcher = Dispatchers.Default,
        )
    }

    single {
        DiffusionInferenceRepository(
            runner = get(),
            deviceCapabilities = get(),
            settingsRepository = get(),
            snapshotProvider = get(),
            suitabilityEngine = get(),
            recommendationPolicy = get(),
            loadRecoveryRepository = get(),
            artifactIdentityResolver = get(),
            loadSessionCoordinator = get(),
            engineVersion = NATIVE_LOAD_ENGINE_VERSION,
            observationRecorder = get(),
        )
    }

    single<InferenceRepository> {
        LlamaInferenceRepository(
            runner = get(),
            deviceCapabilities = get(),
            settingsRepository = get(),
            snapshotProvider = get(),
            suitabilityEngine = get(),
            recommendationPolicy = get(),
            loadRecoveryRepository = get(),
            artifactIdentityResolver = get(),
            loadSessionCoordinator = get(),
            engineVersion = NATIVE_LOAD_ENGINE_VERSION,
            observationRecorder = get(),
        )
    }

    single { ChatConfig() }

    factory { GetAvailableModelsUseCase(get(), get()) }
    factory { GenerateResponseUseCase(get(), get()) }
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
            quickCalibrationRunner = get(),
            calibrationSource = get(),
            downloadCoordinator = get(),
        )
    }
    viewModel {
        DownloadedModelsViewModel(
            localModelRepository = get(),
            storagePathProvider = get(),
            removalService = get(),
        )
    }
    viewModel {
        SettingsViewModel(
            repository = get(),
            quickCalibrationRunner = get(),
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
            generatedMediaStore = get(),
            installedModelLoadRequestResolver = get<InstalledModelLoadRequestResolver>(),
        )
    }
}

internal fun installedModelManifestSource(
    validatedBundle: suspend (String) -> ArtifactManifest?,
): InstalledModelManifestSource = InstalledModelManifestSource(validatedBundle)

private class RecommendationCalibrationScope(val scope: CoroutineScope)
class DownloadRuntimeScope(val scope: CoroutineScope)
