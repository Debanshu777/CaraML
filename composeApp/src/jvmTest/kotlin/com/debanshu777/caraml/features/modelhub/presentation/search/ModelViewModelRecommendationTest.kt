package com.debanshu777.caraml.features.modelhub.presentation.search

import com.debanshu777.caraml.core.data.settings.SettingsRepository
import com.debanshu777.caraml.core.download.DownloadArtifactRequest
import com.debanshu777.caraml.core.download.DownloadArtifactSnapshot
import com.debanshu777.caraml.core.download.DownloadArtifactState
import com.debanshu777.caraml.core.download.DownloadBatchRequest
import com.debanshu777.caraml.core.download.DownloadBatchSnapshot
import com.debanshu777.caraml.core.download.DownloadBatchState
import com.debanshu777.caraml.core.download.DownloadCheckpointCleaner
import com.debanshu777.caraml.core.download.DownloadCoordinator
import com.debanshu777.caraml.core.download.DownloadFailureCode
import com.debanshu777.caraml.core.download.DownloadNotificationPermissionController
import com.debanshu777.caraml.core.download.DownloadTaskStore
import com.debanshu777.caraml.core.download.DownloadUserIntent
import com.debanshu777.caraml.core.download.PlatformDownloadScheduler
import com.debanshu777.caraml.core.download.pendingEvidence
import com.debanshu777.caraml.core.download.toModelFileIdentity
import com.debanshu777.caraml.core.platform.DeviceCapabilities
import com.debanshu777.caraml.core.platform.DeviceSnapshot
import com.debanshu777.caraml.core.platform.HardwareProfile
import com.debanshu777.caraml.core.platform.MemoryTopology
import com.debanshu777.caraml.core.platform.PowerPolicyState
import com.debanshu777.caraml.core.platform.ResourcePoolConfidence
import com.debanshu777.caraml.core.platform.ResourceSnapshot
import com.debanshu777.caraml.core.platform.ThermalState
import com.debanshu777.caraml.core.recommendation.AssessedPlans
import com.debanshu777.caraml.core.recommendation.AssessmentConfidence
import com.debanshu777.caraml.core.recommendation.AssessmentReason
import com.debanshu777.caraml.core.recommendation.Compatibility
import com.debanshu777.caraml.core.recommendation.CalibrationCorrection
import com.debanshu777.caraml.core.recommendation.CalibrationKey
import com.debanshu777.caraml.core.recommendation.CalibrationSource
import com.debanshu777.caraml.core.recommendation.BackendPerformanceProfile
import com.debanshu777.caraml.core.recommendation.Confidence
import com.debanshu777.caraml.core.recommendation.DiffusionComponentDescriptor
import com.debanshu777.caraml.core.recommendation.DiffusionMode
import com.debanshu777.caraml.core.recommendation.DiffusionModelDescriptor
import com.debanshu777.caraml.core.recommendation.FitBand
import com.debanshu777.caraml.core.recommendation.LlmModelDescriptor
import com.debanshu777.caraml.core.recommendation.ModelAssessment
import com.debanshu777.caraml.core.recommendation.ModelDescriptor
import com.debanshu777.caraml.core.recommendation.ModelFileIdentity
import com.debanshu777.caraml.core.recommendation.NoCalibrationSource
import com.debanshu777.caraml.core.recommendation.PersonalizedRecommendation
import com.debanshu777.caraml.core.recommendation.QuantizationEvidence
import com.debanshu777.caraml.core.recommendation.RecommendationCategory
import com.debanshu777.caraml.core.recommendation.RecommendationProfile
import com.debanshu777.caraml.core.recommendation.RecommendationSortKey
import com.debanshu777.caraml.core.recommendation.WorkloadConfig
import com.debanshu777.caraml.core.recommendation.storage.InstalledEvidenceState
import com.debanshu777.caraml.core.recommendation.storage.PersistedModelEvidenceCodec
import com.debanshu777.caraml.core.settings.AppSettings
import com.debanshu777.caraml.core.storage.component.ComponentRepository
import com.debanshu777.caraml.core.storage.component.DownloadedComponentDao
import com.debanshu777.caraml.core.storage.component.DownloadedComponentEntity
import com.debanshu777.caraml.core.storage.component.ModelComponentLinkEntity
import com.debanshu777.caraml.core.storage.localmodel.LocalModelDao
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.core.storage.localmodel.LocalModelRepository
import com.debanshu777.caraml.core.storage.localmodel.ModelType
import com.debanshu777.caraml.core.domain.ModelReadinessReconciler
import com.debanshu777.caraml.features.modelhub.domain.ModelMetadataSource
import com.debanshu777.caraml.features.modelhub.domain.ModelRecommendationService
import com.debanshu777.caraml.features.modelhub.domain.DescriptorState
import com.debanshu777.caraml.features.modelhub.domain.RecommendationSnapshotSource
import com.debanshu777.caraml.features.modelhub.domain.RecommendationVariantEvaluator
import com.debanshu777.caraml.features.modelhub.domain.RepositoryVariant
import com.debanshu777.caraml.features.modelhub.domain.RepositoryVariantSet
import com.debanshu777.huggingfacemanager.HuggingFaceApi
import com.debanshu777.huggingfacemanager.api.RemoteHuggingFaceApiService
import com.debanshu777.huggingfacemanager.download.DownloadManager
import com.debanshu777.huggingfacemanager.download.DownloadArtifactIdentity
import com.debanshu777.huggingfacemanager.download.DownloadMetadataDTO
import com.debanshu777.huggingfacemanager.download.ArtifactManifest
import com.debanshu777.huggingfacemanager.download.ArtifactManifestEntry
import com.debanshu777.huggingfacemanager.download.ArtifactManifestStore
import com.debanshu777.huggingfacemanager.download.ArtifactBundleManifestStore
import com.debanshu777.huggingfacemanager.download.artifactBundleId
import com.debanshu777.huggingfacemanager.download.StoragePathProvider
import com.debanshu777.huggingfacemanager.model.DIFFUSERS_BUNDLE_DB_FILENAME
import com.debanshu777.huggingfacemanager.repository.HuggingFaceRepository
import com.debanshu777.huggingfacemanager.usecase.GetModelConfigUseCase
import com.debanshu777.huggingfacemanager.usecase.GetModelDetailUseCase
import com.debanshu777.huggingfacemanager.usecase.GetModelFileTreeUseCase
import com.debanshu777.huggingfacemanager.usecase.GetRecommendationModelDetailUseCase
import com.debanshu777.huggingfacemanager.usecase.ListModelsUseCase
import com.debanshu777.huggingfacemanager.usecase.ListRecommendationModelsUseCase
import com.debanshu777.huggingfacemanager.usecase.SearchModelsUseCase
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ModelViewModelRecommendationTest {
    @Test
    fun assessedLanguageDownloadEnqueuesCompleteDescriptorEvidence() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val repositoryId = "org/evidenced-language"
        val revision = "a".repeat(40)
        val path = "weights/model-Q4_K_M.gguf"
        val objectId = "b".repeat(64)
        val descriptorFile = modelFileIdentity(repositoryId, revision, path, objectId)
        val client = exactDetailClient(dispatcher, repositoryId, revision, path, objectId)
        val store = ObservingDownloadTaskStore()
        try {
            val viewModel = viewModel(
                client = client,
                dispatcher = dispatcher,
                storagePathProvider = FakeStoragePathProvider(availableStorageBytes = 4L * 1024 * 1024 * 1024),
                recommendationService = recommendationService(
                    dispatcher = dispatcher,
                    descriptorFiles = listOf(descriptorFile),
                    recommendationCategory = RecommendationCategory.NEEDS_INFORMATION,
                ),
                downloadCoordinator = observingDownloadCoordinator(store),
            )

            viewModel.loadDetail(repositoryId, ModelHubBrowseMode.LanguageModels)
            advanceUntilIdle()
            val file = viewModel.ggufFiles.value.single()
            val artifact = requireNotNull(file.artifact)
            viewModel.startDownload(repositoryId, path, metadata(artifact))
            advanceUntilIdle()

            assertTrue(
                store.createdRequests.isNotEmpty(),
                "error=${viewModel.downloadError.value} recommendation=${viewModel.recommendedModels.value}",
            )
            val decoded = PersistedModelEvidenceCodec().decode(store.createdRequests.single().evidence)
            assertEquals(InstalledEvidenceState.COMPLETE, decoded.state)
            assertEquals(viewModel.recommendedModels.value.single().selectedDescriptor, decoded.descriptor)
            assertEquals(listOf(descriptorFile), decoded.artifactIdentities)
        } finally {
            client.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun needsInformationLanguageDownloadStillEnqueuesEnrichmentEvidence() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val repositoryId = "org/unknown-language"
        val revision = "a".repeat(40)
        val path = "weights/model-Q4_K_M.gguf"
        val objectId = "b".repeat(64)
        val client = exactDetailClient(dispatcher, repositoryId, revision, path, objectId)
        val store = ObservingDownloadTaskStore()
        try {
            val viewModel = viewModel(
                client = client,
                dispatcher = dispatcher,
                storagePathProvider = FakeStoragePathProvider(availableStorageBytes = 4L * 1024 * 1024 * 1024),
                recommendationService = recommendationService(
                    dispatcher = dispatcher,
                    variantSet = { id -> RepositoryVariantSet.NeedsInformation(id) },
                ),
                downloadCoordinator = observingDownloadCoordinator(store),
            )

            viewModel.loadDetail(repositoryId, ModelHubBrowseMode.LanguageModels)
            advanceUntilIdle()
            assertEquals(DescriptorState.NEEDS_INFORMATION, viewModel.recommendedModels.value.single().descriptorState)
            val artifact = requireNotNull(viewModel.ggufFiles.value.single().artifact)
            viewModel.startDownload(repositoryId, path, metadata(artifact))
            advanceUntilIdle()

            val decoded = PersistedModelEvidenceCodec().decode(store.createdRequests.single().evidence)
            assertEquals(InstalledEvidenceState.REQUIRES_ENRICHMENT, decoded.state)
            assertNull(decoded.descriptor)
            assertEquals(artifact.toModelFileIdentity(), decoded.artifactIdentities.single())
        } finally {
            client.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun languageDescriptorWithAnExtraIdentityEnqueuesEnrichmentInsteadOfStaleEvidence() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val repositoryId = "org/stale-language"
        val revision = "a".repeat(40)
        val path = "weights/model-Q4_K_M.gguf"
        val objectId = "b".repeat(64)
        val selected = modelFileIdentity(repositoryId, revision, path, objectId)
        val staleExtra = modelFileIdentity(
            repositoryId,
            revision,
            "weights/model-Q8_0.gguf",
            "c".repeat(64),
        )
        val client = exactDetailClient(dispatcher, repositoryId, revision, path, objectId)
        val store = ObservingDownloadTaskStore()
        try {
            val viewModel = viewModel(
                client = client,
                dispatcher = dispatcher,
                storagePathProvider = FakeStoragePathProvider(availableStorageBytes = 4L * 1024 * 1024 * 1024),
                recommendationService = recommendationService(
                    dispatcher = dispatcher,
                    descriptorFiles = listOf(selected, staleExtra),
                    recommendationCategory = RecommendationCategory.NEEDS_INFORMATION,
                ),
                downloadCoordinator = observingDownloadCoordinator(store),
            )

            viewModel.loadDetail(repositoryId, ModelHubBrowseMode.LanguageModels)
            advanceUntilIdle()
            val artifact = requireNotNull(viewModel.ggufFiles.value.single().artifact)
            viewModel.startDownload(repositoryId, path, metadata(artifact))
            advanceUntilIdle()

            val decoded = PersistedModelEvidenceCodec().decode(store.createdRequests.single().evidence)
            assertEquals(InstalledEvidenceState.REQUIRES_ENRICHMENT, decoded.state)
            assertNull(decoded.descriptor)
            assertEquals(artifact.toModelFileIdentity(), decoded.artifactIdentities.single())
        } finally {
            client.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun selectVariantWithoutDescriptorEnqueuesExactArtifactWithEnrichmentEvidence() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val repositoryId = "org/select-variant"
        val revision = "a".repeat(40)
        val path = "weights/model-Q4_K_M.gguf"
        val objectId = "b".repeat(64)
        val client = exactDetailClient(dispatcher, repositoryId, revision, path, objectId)
        val store = ObservingDownloadTaskStore()
        try {
            val viewModel = viewModel(
                client = client,
                dispatcher = dispatcher,
                recommendationService = recommendationService(
                    dispatcher = dispatcher,
                    variantSet = { id -> RepositoryVariantSet.SelectVariant(id) },
                ),
                downloadCoordinator = observingDownloadCoordinator(store),
            )

            viewModel.loadDetail(repositoryId, ModelHubBrowseMode.LanguageModels)
            advanceUntilIdle()
            assertEquals(DescriptorState.SELECT_VARIANT, viewModel.recommendedModels.value.single().descriptorState)
            assertNull(viewModel.recommendedModels.value.single().selectedDescriptor)
            val artifact = requireNotNull(viewModel.ggufFiles.value.single().artifact)
            viewModel.startDownload(repositoryId, path, metadata(artifact))
            advanceUntilIdle()

            val decoded = PersistedModelEvidenceCodec().decode(store.createdRequests.single().evidence)
            assertEquals(InstalledEvidenceState.REQUIRES_ENRICHMENT, decoded.state)
            assertNull(decoded.descriptor)
            assertEquals(artifact.toModelFileIdentity(), decoded.artifactIdentities.single())
        } finally {
            client.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun descriptorMissingRequestedIdentityEnqueuesExactArtifactWithEnrichmentEvidence() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val repositoryId = "org/missing-requested-identity"
        val revision = "a".repeat(40)
        val path = "weights/model-Q4_K_M.gguf"
        val objectId = "b".repeat(64)
        val staleDescriptorFile = modelFileIdentity(
            repositoryId,
            revision,
            "weights/model-Q8_0.gguf",
            "c".repeat(64),
        )
        val client = exactDetailClient(dispatcher, repositoryId, revision, path, objectId)
        val store = ObservingDownloadTaskStore()
        try {
            val viewModel = viewModel(
                client = client,
                dispatcher = dispatcher,
                storagePathProvider = FakeStoragePathProvider(availableStorageBytes = 4L * 1024 * 1024 * 1024),
                recommendationService = recommendationService(
                    dispatcher = dispatcher,
                    descriptorFiles = listOf(staleDescriptorFile),
                    recommendationCategory = RecommendationCategory.NEEDS_INFORMATION,
                ),
                downloadCoordinator = observingDownloadCoordinator(store),
            )

            viewModel.loadDetail(repositoryId, ModelHubBrowseMode.LanguageModels)
            advanceUntilIdle()
            val artifact = requireNotNull(viewModel.ggufFiles.value.single().artifact)
            viewModel.startDownload(repositoryId, path, metadata(artifact))
            advanceUntilIdle()

            val decoded = PersistedModelEvidenceCodec().decode(store.createdRequests.single().evidence)
            assertEquals(InstalledEvidenceState.REQUIRES_ENRICHMENT, decoded.state)
            assertNull(decoded.descriptor)
            assertEquals(artifact.toModelFileIdentity(), decoded.artifactIdentities.single())
        } finally {
            client.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun descriptorRemoteIdentityMismatchEnqueuesExactArtifactWithEnrichmentEvidence() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val repositoryId = "org/remote-id-mismatch"
        val revision = "a".repeat(40)
        val path = "weights/model-Q4_K_M.gguf"
        val exactObjectId = "b".repeat(64)
        val staleDescriptorFile = modelFileIdentity(repositoryId, revision, path, "c".repeat(64))
        val client = exactDetailClient(dispatcher, repositoryId, revision, path, exactObjectId)
        val store = ObservingDownloadTaskStore()
        try {
            val viewModel = viewModel(
                client = client,
                dispatcher = dispatcher,
                storagePathProvider = FakeStoragePathProvider(availableStorageBytes = 4L * 1024 * 1024 * 1024),
                recommendationService = recommendationService(
                    dispatcher = dispatcher,
                    descriptorFiles = listOf(staleDescriptorFile),
                    recommendationCategory = RecommendationCategory.NEEDS_INFORMATION,
                ),
                downloadCoordinator = observingDownloadCoordinator(store),
            )

            viewModel.loadDetail(repositoryId, ModelHubBrowseMode.LanguageModels)
            advanceUntilIdle()
            val artifact = requireNotNull(viewModel.ggufFiles.value.single().artifact)
            viewModel.startDownload(repositoryId, path, metadata(artifact))
            advanceUntilIdle()

            val decoded = PersistedModelEvidenceCodec().decode(store.createdRequests.single().evidence)
            assertEquals(InstalledEvidenceState.REQUIRES_ENRICHMENT, decoded.state)
            assertNull(decoded.descriptor)
            assertEquals(artifact.toModelFileIdentity(), decoded.artifactIdentities.single())
        } finally {
            client.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun nonExactCurrentDetailArtifactIsRejectedBeforeInformationalFallback() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val repositoryId = "org/reject-forged-artifact"
        val revision = "a".repeat(40)
        val path = "weights/model-Q4_K_M.gguf"
        val objectId = "b".repeat(64)
        val client = exactDetailClient(dispatcher, repositoryId, revision, path, objectId)
        val store = ObservingDownloadTaskStore()
        try {
            val viewModel = viewModel(
                client = client,
                dispatcher = dispatcher,
                recommendationService = recommendationService(
                    dispatcher = dispatcher,
                    variantSet = { id -> RepositoryVariantSet.SelectVariant(id) },
                ),
                downloadCoordinator = observingDownloadCoordinator(store),
            )

            viewModel.loadDetail(repositoryId, ModelHubBrowseMode.LanguageModels)
            advanceUntilIdle()
            val exact = requireNotNull(viewModel.ggufFiles.value.single().artifact)
            val forged = requireNotNull(
                DownloadArtifactIdentity.create(
                    repositoryId = exact.repositoryId,
                    immutableRevision = exact.immutableRevision,
                    relativePath = exact.relativePath,
                    remoteObjectId = "sha256:${"f".repeat(64)}",
                    expectedBytes = exact.expectedBytes,
                ),
            )
            viewModel.startDownload(repositoryId, path, metadata(forged))
            advanceUntilIdle()

            assertTrue(store.createdRequests.isEmpty())
            assertTrue(viewModel.downloadError.value != null)
        } finally {
            client.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun diffusionDownloadEnqueuesCompletePrimaryAndComponentEvidence() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val repositoryId = "stabilityai/sd-turbo"
        val revision = "a".repeat(40)
        val primaryPath = "model.safetensors"
        val componentPath = "text_encoder_2/model.safetensors"
        val primaryObjectId = "b".repeat(64)
        val componentObjectId = "c".repeat(64)
        val primary = modelFileIdentity(repositoryId, revision, primaryPath, primaryObjectId)
        val component = modelFileIdentity(repositoryId, revision, componentPath, componentObjectId)
        val descriptor = DiffusionModelDescriptor(
            repositoryId = repositoryId,
            revision = revision,
            components = listOf(
                DiffusionComponentDescriptor(
                    file = primary,
                    role = null,
                    required = true,
                    isPrimary = true,
                    quantization = QuantizationEvidence.Known("F16"),
                ),
                DiffusionComponentDescriptor(
                    file = component,
                    role = com.debanshu777.huggingfacemanager.sdcpp.ComponentRole.CLIP_G,
                    required = true,
                    isPrimary = false,
                    quantization = QuantizationEvidence.Known("F16"),
                ),
            ),
            mode = DiffusionMode.IMAGE,
            family = "SDXL",
            architecture = null,
            width = 512,
            height = 512,
            quantizationDistribution = listOf("F16"),
            requiredComponentsPresent = true,
            requiredEngineFeatures = emptyList(),
            evidence = emptyList(),
        )
        val requestDispatcher = dispatcher
        val engine = object : MockEngine(MockEngineConfig().apply {
            reuseHandlers = true
            addHandler { request ->
                when {
                    request.url.encodedPath.contains("/tree/") -> respondJson(
                        """[{"path":"$primaryPath","type":"file","size":100,"lfs":{"oid":"$primaryObjectId","size":100}},{"path":"$componentPath","type":"file","size":100,"lfs":{"oid":"$componentObjectId","size":100}}]""",
                    )
                    request.url.encodedPath.endsWith("/$repositoryId") -> respondJson(
                        """{"id":"$repositoryId","modelId":"$repositoryId","sha":"$revision","private":false}""",
                    )
                    else -> error("Unexpected request ${request.url}")
                }
            }
        }) {
            override val dispatcher: CoroutineDispatcher = requestDispatcher
        }
        val client = HttpClient(engine)
        val store = ObservingDownloadTaskStore()
        try {
            val viewModel = viewModel(
                client = client,
                dispatcher = dispatcher,
                storagePathProvider = FakeStoragePathProvider(availableStorageBytes = 4L * 1024 * 1024 * 1024),
                recommendationService = recommendationService(
                    dispatcher = dispatcher,
                    descriptor = descriptor,
                    recommendationCategory = RecommendationCategory.NEEDS_INFORMATION,
                ),
                downloadCoordinator = observingDownloadCoordinator(store),
            )

            viewModel.loadDetail(repositoryId, ModelHubBrowseMode.DiffusionImage)
            advanceUntilIdle()
            val artifact = requireNotNull(viewModel.ggufFiles.value.single { it.path == primaryPath }.artifact)
            viewModel.startDownload(repositoryId, primaryPath, metadata(artifact))
            advanceUntilIdle()

            assertTrue(
                store.createdRequests.isNotEmpty(),
                "error=${viewModel.downloadError.value} recommendation=${viewModel.recommendedModels.value}",
            )
            val request = store.createdRequests.single()
            val decoded = PersistedModelEvidenceCodec().decode(request.evidence)
            assertEquals(setOf(primaryPath, componentPath), request.artifacts.map { it.metadata.artifact.relativePath }.toSet())
            assertEquals(InstalledEvidenceState.COMPLETE, decoded.state)
            assertEquals(descriptor, decoded.descriptor)
            assertEquals(setOf(primary, component), decoded.artifactIdentities.toSet())
        } finally {
            client.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun freshDetailLoadAssessesExactFilesForDownloadAdmission() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val repositoryId = "org/detail-download"
        val revision = "a".repeat(40)
        val path = "weights/model-Q4_K_M.gguf"
        val objectId = "b".repeat(64)
        val sizeBytes = 100L
        val descriptorFile = ModelFileIdentity(
            repositoryId = repositoryId,
            revision = revision,
            path = path,
            sizeBytes = sizeBytes,
            gitOid = null,
            lfsOid = objectId,
            xetHash = null,
            evidence = emptyList(),
        )
        val requestDispatcher = dispatcher
        val engine = object : MockEngine(MockEngineConfig().apply {
            reuseHandlers = true
            addHandler { request ->
                when {
                    request.url.encodedPath.contains("/tree/") -> respondJson(
                        """[{"path":"$path","type":"file","size":$sizeBytes,"lfs":{"oid":"$objectId","size":$sizeBytes}}]""",
                    )
                    request.url.encodedPath.endsWith("/$repositoryId") -> respondJson(
                        """{"id":"$repositoryId","modelId":"$repositoryId","sha":"$revision","private":false} """,
                    )
                    else -> error("Unexpected request ${request.url}")
                }
            }
        }) {
            override val dispatcher: CoroutineDispatcher = requestDispatcher
        }
        val client = HttpClient(engine)
        try {
            val viewModel = viewModel(
                client = client,
                dispatcher = dispatcher,
                recommendationService = recommendationService(
                    dispatcher = dispatcher,
                    descriptorFiles = listOf(descriptorFile),
                    recommendationStorageFit = FitBand.COMFORTABLE,
                ),
            )

            viewModel.loadDetail(repositoryId, ModelHubBrowseMode.LanguageModels)
            advanceUntilIdle()

            val recommendation = viewModel.recommendedModels.value.single()
            val descriptor = recommendation.selectedDescriptor as LlmModelDescriptor
            assertEquals(repositoryId, recommendation.repositoryId)
            assertEquals(listOf(descriptorFile), descriptor.files)
            assertEquals(path, viewModel.ggufFiles.value.single().artifact?.relativePath)
        } finally {
            client.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun durableProjectionUsesExactSelectedTaskAcrossMultipleBatches() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val repositoryId = "org/multi-batch-diffusion"
        val revision = "a".repeat(40)
        val selectedPath = "unet/model-q4.safetensors"
        val otherPath = "unet/model-q8.safetensors"
        val selectedObjectId = "b".repeat(64)
        val otherObjectId = "c".repeat(64)
        val selectedArtifact = requireNotNull(
            DownloadArtifactIdentity.create(
                repositoryId = repositoryId,
                immutableRevision = revision,
                relativePath = selectedPath,
                remoteObjectId = "sha256:$selectedObjectId",
                expectedBytes = 100L,
            ),
        )
        val otherArtifact = requireNotNull(
            DownloadArtifactIdentity.create(
                repositoryId = repositoryId,
                immutableRevision = revision,
                relativePath = otherPath,
                remoteObjectId = "sha256:$otherObjectId",
                expectedBytes = 200L,
            ),
        )
        val selectedPaused = durableSnapshot(
            batchId = "selected-paused",
            artifact = selectedArtifact,
            artifactState = DownloadArtifactState.PAUSED,
            batchState = DownloadBatchState.PAUSED,
            bytesReceived = 40L,
        )
        val otherRunning = durableSnapshot(
            batchId = "other-running",
            artifact = otherArtifact,
            artifactState = DownloadArtifactState.RUNNING,
            batchState = DownloadBatchState.RUNNING,
            bytesReceived = 100L,
        )
        val requestDispatcher = dispatcher
        val engine = object : MockEngine(MockEngineConfig().apply {
            reuseHandlers = true
            addHandler { request ->
                when {
                    request.url.encodedPath.contains("/tree/") -> respondJson(
                        """[{"path":"$selectedPath","type":"file","size":100,"lfs":{"oid":"$selectedObjectId","size":100}},{"path":"$otherPath","type":"file","size":200,"lfs":{"oid":"$otherObjectId","size":200}}]""",
                    )
                    request.url.encodedPath.endsWith("/$repositoryId") -> respondJson(
                        """{"id":"$repositoryId","modelId":"$repositoryId","sha":"$revision","private":false}""",
                    )
                    else -> error("Unexpected request ${request.url}")
                }
            }
        }) {
            override val dispatcher: CoroutineDispatcher = requestDispatcher
        }
        val client = HttpClient(engine)
        val store = ObservingDownloadTaskStore().apply {
            snapshots.value = listOf(selectedPaused, otherRunning)
        }
        try {
            val viewModel = viewModel(
                client = client,
                dispatcher = dispatcher,
                downloadCoordinator = observingDownloadCoordinator(store),
            )
            backgroundScope.launch { viewModel.installBundleState.collect {} }
            viewModel.loadDetail(repositoryId, ModelHubBrowseMode.DiffusionImage)
            advanceUntilIdle()

            assertTrue(viewModel.isDownloading.value)
            assertEquals(otherArtifact, viewModel.activeDownloadArtifact.value)
            assertEquals(
                selectedArtifact,
                viewModel.ggufFiles.value.single { it.path == selectedPath }.artifact,
            )
            assertEquals(
                otherArtifact,
                viewModel.ggufFiles.value.single { it.path == otherPath }.artifact,
            )
            assertEquals(
                null,
                viewModel.ggufFiles.value.single { it.path == selectedPath }.progress,
            )
            assertEquals(
                50f,
                viewModel.ggufFiles.value.single { it.path == otherPath }.progress,
            )
            assertEquals(40L, viewModel.installBundleState.value.overallBytesReceived)
            assertEquals(100L, viewModel.installBundleState.value.overallBytesTotal)
            assertEquals(0.4f, viewModel.installBundleState.value.overallProgress)

            val selectedRetryable = durableSnapshot(
                batchId = "selected-retryable",
                artifact = selectedArtifact,
                artifactState = DownloadArtifactState.FAILED_RETRYABLE,
                batchState = DownloadBatchState.FAILED_RETRYABLE,
                bytesReceived = 40L,
            )
            store.snapshots.value = listOf(otherRunning, selectedRetryable)
            advanceUntilIdle()

            assertTrue(viewModel.isDownloading.value)
            assertEquals(otherArtifact, viewModel.activeDownloadArtifact.value)
            assertEquals(40L, viewModel.installBundleState.value.overallBytesReceived)
            assertEquals(100L, viewModel.installBundleState.value.overallBytesTotal)
            assertEquals(0.4f, viewModel.installBundleState.value.overallProgress)
            assertEquals(
                "Download paused after a problem. Retry when ready.",
                viewModel.downloadError.value,
            )

            viewModel.selectVariant(otherPath)
            advanceUntilIdle()

            assertEquals(100L, viewModel.installBundleState.value.overallBytesReceived)
            assertEquals(200L, viewModel.installBundleState.value.overallBytesTotal)
            assertEquals(0.5f, viewModel.installBundleState.value.overallProgress)
            assertEquals(null, viewModel.downloadError.value)

            viewModel.selectVariant(selectedPath)
            advanceUntilIdle()

            assertEquals(40L, viewModel.installBundleState.value.overallBytesReceived)
            assertEquals(100L, viewModel.installBundleState.value.overallBytesTotal)
            assertEquals(
                "Download paused after a problem. Retry when ready.",
                viewModel.downloadError.value,
            )
        } finally {
            client.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun durableCompletionRefreshesRepositoryStateOutsideSelectedBatch() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val repositoryId = "org/multi-batch-completion"
        val revision = "d".repeat(40)
        val selectedPath = "weights/model-q4.gguf"
        val completedPath = "weights/model-q8.gguf"
        val selectedObjectId = "e".repeat(64)
        val completedObjectId = "f".repeat(64)
        val requestDispatcher = dispatcher
        val engine = object : MockEngine(MockEngineConfig().apply {
            reuseHandlers = true
            addHandler { request ->
                when {
                    request.url.encodedPath.contains("/tree/") -> respondJson(
                        """[{"path":"$selectedPath","type":"file","size":100,"lfs":{"oid":"$selectedObjectId","size":100}},{"path":"$completedPath","type":"file","size":200,"lfs":{"oid":"$completedObjectId","size":200}}]""",
                    )
                    request.url.encodedPath.endsWith("/$repositoryId") -> respondJson(
                        """{"id":"$repositoryId","modelId":"$repositoryId","sha":"$revision","private":false}""",
                    )
                    else -> error("Unexpected request ${request.url}")
                }
            }
        }) {
            override val dispatcher: CoroutineDispatcher = requestDispatcher
        }
        val client = HttpClient(engine)
        val store = ObservingDownloadTaskStore()
        val localModelDao = FakeLocalModelDao()
        try {
            val viewModel = viewModel(
                client = client,
                dispatcher = dispatcher,
                localModelDao = localModelDao,
                downloadCoordinator = observingDownloadCoordinator(store),
            )
            viewModel.loadDetail(repositoryId, ModelHubBrowseMode.LanguageModels)
            advanceUntilIdle()
            viewModel.selectVariant(selectedPath)
            advanceUntilIdle()
            val initialQueries = localModelDao.filenameQueryCount
            val selectedArtifact = requireNotNull(
                viewModel.ggufFiles.value.single { it.path == selectedPath }.artifact,
            )
            val completedArtifact = requireNotNull(
                viewModel.ggufFiles.value.single { it.path == completedPath }.artifact,
            )

            store.snapshots.value = listOf(
                durableSnapshot(
                    batchId = "selected-paused",
                    artifact = selectedArtifact,
                    artifactState = DownloadArtifactState.PAUSED,
                    batchState = DownloadBatchState.PAUSED,
                    bytesReceived = 40L,
                ),
                durableSnapshot(
                    batchId = "other-completed",
                    artifact = completedArtifact,
                    artifactState = DownloadArtifactState.COMPLETED,
                    batchState = DownloadBatchState.COMPLETED,
                    bytesReceived = completedArtifact.expectedBytes,
                ),
            )
            advanceUntilIdle()

            assertEquals(initialQueries + 1, localModelDao.filenameQueryCount)
        } finally {
            client.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun restoredCompletedDiffusionBatchRefreshesAfterDetailHydration() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val repositoryId = "org/restored-diffusion"
        val revision = "1".repeat(40)
        val path = "checkpoint.safetensors"
        val bytes = "verified diffusion checkpoint".encodeToByteArray()
        val objectId = bytes.sha256()
        val artifact = requireNotNull(
            DownloadArtifactIdentity.create(
                repositoryId = repositoryId,
                immutableRevision = revision,
                relativePath = path,
                remoteObjectId = "sha256:$objectId",
                expectedBytes = bytes.size.toLong(),
            ),
        )
        val requestDispatcher = dispatcher
        val engine = object : MockEngine(MockEngineConfig().apply {
            reuseHandlers = true
            addHandler { request ->
                when {
                    request.url.encodedPath.contains("/tree/") -> respondJson(
                        """[{"path":"$path","type":"file","size":${bytes.size},"lfs":{"oid":"$objectId","size":${bytes.size}}}]""",
                    )
                    request.url.encodedPath.endsWith("/$repositoryId") -> respondJson(
                        """{"id":"$repositoryId","modelId":"$repositoryId","sha":"$revision","private":false}""",
                    )
                    else -> error("Unexpected request ${request.url}")
                }
            }
        }) {
            override val dispatcher: CoroutineDispatcher = requestDispatcher
        }
        val client = HttpClient(engine)
        val trusted = Files.createTempDirectory("caraml-restored-complete-").toRealPath().toFile()
        val storage = FakeStoragePathProvider(trusted, realFileAccess = true)
        val store = ObservingDownloadTaskStore().apply {
            snapshots.value = listOf(
                durableSnapshot(
                    batchId = "restored-completed",
                    artifact = artifact,
                    artifactState = DownloadArtifactState.COMPLETED,
                    batchState = DownloadBatchState.COMPLETED,
                    bytesReceived = artifact.expectedBytes,
                ),
            )
        }
        try {
            writeVerifiedBundle(
                storage = storage,
                repositoryId = repositoryId,
                revision = revision,
                files = listOf(Triple(path, "model", bytes)),
            )
            val viewModel = viewModel(
                client = client,
                dispatcher = dispatcher,
                storagePathProvider = storage,
                downloadManager = DownloadManager(storage),
                downloadCoordinator = observingDownloadCoordinator(store),
            )
            backgroundScope.launch { viewModel.installBundleState.collect {} }

            viewModel.loadDetail(repositoryId, ModelHubBrowseMode.DiffusionImage)
            advanceUntilIdle()

            assertTrue(viewModel.ggufFiles.value.single().isDownloaded)
            assertTrue(viewModel.installBundleState.value.isReady)
        } finally {
            client.close()
            trusted.deleteRecursively()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun needsInformationAllowsDownloadOnlyForAnExactCurrentDetailArtifact() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val repositoryId = "org/detail-needs-information"
        val revision = "a".repeat(40)
        val path = "weights/model-Q4_K_M.gguf"
        val body = "exact model bytes".encodeToByteArray()
        val objectId = body.sha256()
        val requestDispatcher = dispatcher
        val engine = object : MockEngine(MockEngineConfig().apply {
            reuseHandlers = true
            addHandler { request ->
                when {
                    request.url.encodedPath.contains("/tree/") -> respondJson(
                        """[{"path":"$path","type":"file","size":${body.size},"lfs":{"oid":"$objectId","size":${body.size}}}]""",
                    )
                    request.url.encodedPath.endsWith("/$repositoryId") -> respondJson(
                        """{"id":"$repositoryId","modelId":"$repositoryId","sha":"$revision","private":false}""",
                    )
                    else -> error("Unexpected request ${request.url}")
                }
            }
        }) {
            override val dispatcher: CoroutineDispatcher = requestDispatcher
        }
        val client = HttpClient(engine)
        val store = ObservingDownloadTaskStore()
        try {
            val viewModel = viewModel(
                client = client,
                dispatcher = dispatcher,
                recommendationService = recommendationService(
                    dispatcher = dispatcher,
                    variantSet = { id -> RepositoryVariantSet.NeedsInformation(id) },
                ),
                downloadCoordinator = observingDownloadCoordinator(store),
            )

            viewModel.loadDetail(repositoryId, ModelHubBrowseMode.LanguageModels)
            advanceUntilIdle()

            val file = viewModel.ggufFiles.value.single()
            val artifact = requireNotNull(file.artifact)
            assertEquals(DescriptorState.NEEDS_INFORMATION, viewModel.recommendedModels.value.single().descriptorState)

            val forgedArtifact = requireNotNull(
                DownloadArtifactIdentity.create(
                    repositoryId = repositoryId,
                    immutableRevision = revision,
                    relativePath = path,
                    remoteObjectId = "sha256:${"f".repeat(64)}",
                    expectedBytes = body.size.toLong(),
                ),
            )
            viewModel.startDownload(
                repositoryId,
                path,
                DownloadMetadataDTO(
                    artifact = forgedArtifact,
                    logicalRole = "model",
                    sizeBytes = forgedArtifact.expectedBytes,
                    author = null,
                    libraryName = null,
                    pipelineTag = null,
                ),
            )
            advanceUntilIdle()

            assertFalse(viewModel.isDownloading.value)
            assertTrue(viewModel.downloadError.value != null)

            viewModel.startDownload(
                repositoryId,
                path,
                DownloadMetadataDTO(
                    artifact = artifact,
                    logicalRole = "model",
                    sizeBytes = artifact.expectedBytes,
                    author = null,
                    libraryName = null,
                    pipelineTag = null,
                ),
            )
            advanceUntilIdle()

            assertTrue(
                viewModel.isDownloading.value,
                "A validated detail artifact should download while recommendation info is incomplete: ${viewModel.downloadError.value}",
            )
            val request = store.createdRequests.single()
            assertEquals(artifact, request.artifacts.single().metadata.artifact)
        } finally {
            client.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun confirmedLanguageDownloadEnqueuesExactDurableRequest() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val repositoryId = "org/download-state"
        val path = "weights/model-00001-of-00002.gguf"
        val revision = "a".repeat(40)
        val objectId = "b".repeat(40)
        val expectedBytes = 100L
        val descriptorFile = ModelFileIdentity(
            repositoryId = repositoryId,
            revision = revision,
            path = path,
            sizeBytes = expectedBytes,
            gitOid = objectId,
            lfsOid = null,
            xetHash = null,
            evidence = emptyList(),
        )
        val artifact = requireNotNull(
            DownloadArtifactIdentity.create(
                repositoryId = repositoryId,
                immutableRevision = revision,
                relativePath = path,
                remoteObjectId = objectId,
                expectedBytes = expectedBytes,
            ),
        )
        val metadata = DownloadMetadataDTO(
            artifact = artifact,
            logicalRole = "model",
            sizeBytes = artifact.expectedBytes,
            author = null,
            libraryName = null,
            pipelineTag = null,
        )
        val requestDispatcher = dispatcher
        val engine = object : MockEngine(MockEngineConfig().apply {
            reuseHandlers = true
            addHandler { request ->
                when {
                    request.url.encodedPath.contains("/tree/") -> respondJson(
                        """[{"path":"$path","type":"file","size":100,"oid":"$objectId"}]""",
                    )
                    request.url.encodedPath.endsWith("/$repositoryId") -> respondJson(
                        """{"id":"$repositoryId","modelId":"$repositoryId","sha":"$revision","private":false}""",
                    )
                    else -> error("Unexpected request ${request.url}")
                }
            }
        }) {
            override val dispatcher: CoroutineDispatcher = requestDispatcher
        }
        val client = HttpClient(engine)
        val store = ObservingDownloadTaskStore()
        try {
            val viewModel = viewModel(
                client = client,
                dispatcher = dispatcher,
                storagePathProvider = FakeStoragePathProvider(
                    availableStorageBytes = 1024L * 1024 * 1024,
                ),
                recommendationService = recommendationService(
                    dispatcher = dispatcher,
                    descriptorFiles = listOf(descriptorFile),
                    recommendationCategory = RecommendationCategory.NOT_SUITABLE,
                    recommendationStorageFit = FitBand.COMFORTABLE,
                ),
                downloadCoordinator = observingDownloadCoordinator(store),
            )
            viewModel.loadDetail(repositoryId, ModelHubBrowseMode.LanguageModels)
            advanceUntilIdle()

            viewModel.startDownload(repositoryId, path, metadata)
            advanceUntilIdle()

            assertTrue(
                viewModel.showDownloadForLaterConfirmation.value,
                "recommendations=${viewModel.recommendedModels.value}, error=${viewModel.downloadError.value}",
            )
            assertFalse(viewModel.isDownloading.value)
            assertEquals(null, viewModel.activeDownloadArtifact.value)

            viewModel.confirmDownloadForLater()
            advanceUntilIdle()

            assertTrue(viewModel.isDownloading.value)
            assertEquals(null, viewModel.activeDownloadArtifact.value)
            assertFalse(viewModel.showDownloadForLaterConfirmation.value)
            val request = store.createdRequests.single()
            assertTrue(request.downloadForLaterConfirmed)
            assertEquals(artifact, request.artifacts.single().metadata.artifact)
        } finally {
            client.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun diffusionDetailIgnoresStaleRoomBundleSentinelWithoutValidatedManifestEvidence() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val revision = "a".repeat(40)
        val requestDispatcher = dispatcher
        val engine = object : MockEngine(MockEngineConfig().apply {
            reuseHandlers = true
            addHandler { request ->
                when {
                    request.url.encodedPath.endsWith("/tree/$revision") -> respondJson(
                        """[{"path":"model.fp16.safetensors","type":"file","size":4,"lfs":{"oid":"${"b".repeat(64)}","size":4}},{"path":"model.safetensors","type":"file","size":4,"lfs":{"oid":"${"c".repeat(64)}","size":4}}]""",
                    )
                    request.url.encodedPath.endsWith("/segmind/tiny-sd") -> respondJson(
                        """{"id":"segmind/tiny-sd","modelId":"segmind/tiny-sd","sha":"$revision","private":false}""",
                    )
                    else -> error("Unexpected request ${request.url}")
                }
            }
        }) {
            override val dispatcher: CoroutineDispatcher = requestDispatcher
        }
        val client = HttpClient(engine)
        try {
            val viewModel = viewModel(
                client,
                dispatcher,
                localModelDao = FakeLocalModelDao(listOf("__diffusers_bundle__")),
            )

            viewModel.loadDetail("segmind/tiny-sd", ModelHubBrowseMode.DiffusionImage)
            advanceUntilIdle()

            assertTrue(
                viewModel.ggufFiles.value.isNotEmpty(),
                "detail=${viewModel.modelDetail.value?.modelId} error=${viewModel.detailError.value}",
            )
            assertTrue(viewModel.ggufFiles.value.none { it.isDownloaded })
            assertFalse(viewModel.installBundleState.value.isReady)
        } finally {
            client.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun diffusionDetailRequiresValidatedAggregateAndEveryExactComponent() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val revision = "a".repeat(40)
        val primaryPath = "model.safetensors"
        val componentPath = "text_encoder_2/model.safetensors"
        val primaryBytes = "main".encodeToByteArray()
        val componentBytes = "clip".encodeToByteArray()
        val primarySha = primaryBytes.sha256()
        val componentSha = componentBytes.sha256()
        val requestDispatcher = dispatcher
        var detailRequests = 0
        var treeRequests = 0
        val engine = object : MockEngine(MockEngineConfig().apply {
            reuseHandlers = true
            addHandler { request ->
                when {
                    request.url.encodedPath.contains("/tree/") -> {
                        treeRequests += 1
                        respondJson(
                            """[{"path":"$componentPath","type":"file","size":4,"lfs":{"oid":"$componentSha","size":4}},{"path":"$primaryPath","type":"file","size":4,"lfs":{"oid":"$primarySha","size":4}}]""",
                        )
                    }
                    request.url.encodedPath.endsWith("/stabilityai/sd-turbo") -> {
                        detailRequests += 1
                        respondJson(
                            """{"id":"stabilityai/sd-turbo","modelId":"stabilityai/sd-turbo","sha":"$revision","private":false}""",
                        )
                    }
                    else -> error("Unexpected request")
                }
            }
        }) {
            override val dispatcher: CoroutineDispatcher = requestDispatcher
        }
        val client = HttpClient(engine)
        val trusted = Files.createTempDirectory("caraml-vm-manifest-").toRealPath().toFile()
        val storage = FakeStoragePathProvider(trusted, realFileAccess = true)
        try {
            writeVerifiedBundle(
                storage = storage,
                repositoryId = "stabilityai/sd-turbo",
                revision = revision,
                files = listOf(
                    Triple(primaryPath, "model", primaryBytes),
                    Triple(componentPath, "clip_g", componentBytes),
                ),
            )
            val installed = viewModel(client, dispatcher, storagePathProvider = storage)
            backgroundScope.launch { installed.installBundleState.collect {} }
            installed.loadDetail("stabilityai/sd-turbo", ModelHubBrowseMode.DiffusionImage)
            advanceUntilIdle()

            assertTrue(
                installed.installBundleState.value.isReady,
                "files=${installed.ggufFiles.value} components=${installed.setupComponents.value}",
            )
            assertTrue(installed.ggufFiles.value.single { it.path == primaryPath }.isDownloaded)
            assertTrue(installed.setupComponents.value.single { it.filePath == componentPath }.isDownloaded)
            assertEquals(2, detailRequests)
            assertEquals(1, treeRequests)

            File(
                storage.getModelsStorageDirectory("stabilityai/sd-turbo"),
                ArtifactBundleManifestStore.MANIFEST_FILE_NAME,
            ).delete()
            val interrupted = viewModel(client, dispatcher, storagePathProvider = storage)
            backgroundScope.launch { interrupted.installBundleState.collect {} }
            interrupted.loadDetail("stabilityai/sd-turbo", ModelHubBrowseMode.DiffusionImage)
            advanceUntilIdle()
            assertFalse(interrupted.installBundleState.value.isReady)
            assertTrue(interrupted.ggufFiles.value.single { it.path == primaryPath }.isDownloaded)
            assertTrue(interrupted.setupComponents.value.single { it.filePath == componentPath }.isDownloaded)
            assertEquals(4, detailRequests, "restart must not refetch metadata for a valid component")
            assertEquals(2, treeRequests, "restart must not refetch the valid component tree")

            writeVerifiedBundle(
                storage = storage,
                repositoryId = "stabilityai/sd-turbo",
                revision = revision,
                files = listOf(
                    Triple(primaryPath, "model", primaryBytes),
                    Triple(componentPath, "clip_g", componentBytes),
                ),
            )
            File(storage.getModelsStorageDirectory("stabilityai/sd-turbo"), componentPath)
                .writeBytes("evil".encodeToByteArray())
            val corrupted = viewModel(client, dispatcher, storagePathProvider = storage)
            backgroundScope.launch { corrupted.installBundleState.collect {} }
            corrupted.loadDetail("stabilityai/sd-turbo", ModelHubBrowseMode.DiffusionImage)
            advanceUntilIdle()

            assertFalse(corrupted.installBundleState.value.isReady)
            assertTrue(corrupted.ggufFiles.value.none { it.isDownloaded })
            assertTrue(corrupted.setupComponents.value.none { it.isDownloaded })
            assertEquals(7, detailRequests, "corrupt component metadata must be refreshed")
            assertEquals(4, treeRequests, "corrupt component metadata must be refreshed")
        } finally {
            client.close()
            trusted.deleteRecursively()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun readinessReconciliationDemotesAStaleReadyRoomRowWithoutAggregateEvidence() = runTest {
        val trusted = Files.createTempDirectory("caraml-ready-reconcile-").toRealPath().toFile()
        val storage = FakeStoragePathProvider(trusted, realFileAccess = true)
        val model = LocalModelEntity(
            modelId = "segmind/tiny-sd",
            filename = "__diffusers_bundle__",
            localPath = storage.getModelsStorageDirectory("segmind/tiny-sd"),
            sizeBytes = Long.MAX_VALUE,
            downloadedAt = 0L,
            author = null,
            libraryName = null,
            pipelineTag = "text-to-image",
            modelType = "image",
            componentStatus = LocalModelEntity.STATUS_READY,
        )
        val dao = FakeLocalModelDao(mainModels = listOf(model))
        try {
            ModelReadinessReconciler(LocalModelRepository(dao), storage).reconcile()

            assertEquals(LocalModelEntity.STATUS_PARTIAL, dao.updatedStatuses[model.modelId])
        } finally {
            trusted.deleteRecursively()
        }
    }

    @Test
    fun unknownDiffusionSetupWithoutAggregateIsPartial() = runTest {
        val trusted = Files.createTempDirectory("caraml-unknown-ready-missing-").toRealPath().toFile()
        val storage = FakeStoragePathProvider(trusted, realFileAccess = true)
        val model = unknownDiffusionModel(storage, ModelType.IMAGE)
        val dao = FakeLocalModelDao(mainModels = listOf(model))
        try {
            ModelReadinessReconciler(LocalModelRepository(dao), storage).reconcile()

            assertEquals(LocalModelEntity.STATUS_PARTIAL, dao.updatedStatuses[model.modelId])
        } finally {
            trusted.deleteRecursively()
        }
    }

    @Test
    fun unknownDiffusionSetupWithCorruptAggregateIsPartial() = runTest {
        val trusted = Files.createTempDirectory("caraml-unknown-ready-corrupt-").toRealPath().toFile()
        val storage = FakeStoragePathProvider(trusted, realFileAccess = true)
        val model = unknownDiffusionModel(storage, ModelType.VIDEO)
        val bytes = "valid".encodeToByteArray()
        try {
            writeVerifiedBundle(
                storage = storage,
                repositoryId = model.modelId,
                revision = "a".repeat(40),
                files = listOf(Triple("checkpoint.safetensors", "model", bytes)),
            )
            File(storage.getModelsStorageDirectory(model.modelId), "checkpoint.safetensors")
                .writeBytes("evil!".encodeToByteArray())
            val dao = FakeLocalModelDao(mainModels = listOf(model))

            ModelReadinessReconciler(LocalModelRepository(dao), storage).reconcile()

            assertEquals(LocalModelEntity.STATUS_PARTIAL, dao.updatedStatuses[model.modelId])
        } finally {
            trusted.deleteRecursively()
        }
    }

    @Test
    fun unknownDiffusionSetupWithOneValidatedOwnerPrimaryIsReady() = runTest {
        val trusted = Files.createTempDirectory("caraml-unknown-ready-valid-").toRealPath().toFile()
        val storage = FakeStoragePathProvider(trusted, realFileAccess = true)
        val model = unknownDiffusionModel(storage, ModelType.IMAGE).copy(
            componentStatus = LocalModelEntity.STATUS_PARTIAL,
        )
        try {
            writeVerifiedBundle(
                storage = storage,
                repositoryId = model.modelId,
                revision = "a".repeat(40),
                files = listOf(
                    Triple("checkpoint.safetensors", "model", "valid".encodeToByteArray()),
                ),
            )
            val dao = FakeLocalModelDao(mainModels = listOf(model))

            ModelReadinessReconciler(LocalModelRepository(dao), storage).reconcile()

            assertEquals(LocalModelEntity.STATUS_READY, dao.updatedStatuses[model.modelId])
        } finally {
            trusted.deleteRecursively()
        }
    }

    @Test
    fun onlyExplicitStoredLanguageTypeUsesLegacyReadinessBypass() = runTest {
        val trusted = Files.createTempDirectory("caraml-language-ready-").toRealPath().toFile()
        val storage = FakeStoragePathProvider(trusted, realFileAccess = true)
        val model = unknownDiffusionModel(storage, ModelType.TEXT).copy(
            componentStatus = LocalModelEntity.STATUS_PARTIAL,
        )
        val dao = FakeLocalModelDao(mainModels = listOf(model))
        try {
            ModelReadinessReconciler(LocalModelRepository(dao), storage).reconcile()

            assertEquals(LocalModelEntity.STATUS_READY, dao.updatedStatuses[model.modelId])
        } finally {
            trusted.deleteRecursively()
        }
    }

    @Test
    fun laterSearchIntentWinsWhenEarlierResponseCompletesLast() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val requestDispatcher = dispatcher
        val engine = object : MockEngine(MockEngineConfig().apply {
            reuseHandlers = true
            addHandler { request ->
                val query = request.url.parameters["q"]
                if (query == "first") {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                }
                respondJson(searchResponse(query.orEmpty(), "org/$query"))
            }
        }) {
            override val dispatcher: CoroutineDispatcher = requestDispatcher
        }
        val client = HttpClient(engine)
        try {
            val viewModel = viewModel(client, dispatcher)
            viewModel.updateSearchQuery("first")
            viewModel.performSearch()
            firstStarted.await()

            viewModel.updateSearchQuery("second")
            viewModel.performSearch()
            advanceUntilIdle()
            assertEquals("second", viewModel.searchResponse.value?.q)
            assertEquals(listOf("org/second"), viewModel.recommendedModels.value.map { it.repositoryId })

            releaseFirst.complete(Unit)
            advanceUntilIdle()

            assertEquals("second", viewModel.searchResponse.value?.q)
            assertEquals(listOf("org/second"), viewModel.recommendedModels.value.map { it.repositoryId })
        } finally {
            releaseFirst.complete(Unit)
            client.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun browseModeSwitchRejectsLateLanguageModelListResponse() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val listStarted = CompletableDeferred<Unit>()
        val releaseList = CompletableDeferred<Unit>()
        val requestDispatcher = dispatcher
        val engine = object : MockEngine(MockEngineConfig().apply {
            reuseHandlers = true
            addHandler { request ->
                if (request.url.encodedPath == "/models-json") {
                    listStarted.complete(Unit)
                    releaseList.await()
                    respondJson(listResponse("org/stale-language-model"))
                } else {
                    respondJson(searchResponse("unused", "org/unused"))
                }
            }
        }) {
            override val dispatcher: CoroutineDispatcher = requestDispatcher
        }
        val client = HttpClient(engine)
        try {
            val viewModel = viewModel(client, dispatcher)
            viewModel.loadModels()
            listStarted.await()

            viewModel.setBrowseMode(ModelHubBrowseMode.DiffusionImage)
            val curatedIds = viewModel.listResponse.value?.models.orEmpty().filterNotNull().mapNotNull { it.id }
            assertTrue(curatedIds.isNotEmpty())
            assertFalse("org/stale-language-model" in curatedIds)

            releaseList.complete(Unit)
            runCurrent()

            assertEquals(ModelHubBrowseMode.DiffusionImage, viewModel.browseMode.value)
            assertFalse(
                viewModel.listResponse.value?.models.orEmpty().filterNotNull()
                    .mapNotNull { it.id }
                    .contains("org/stale-language-model"),
            )
        } finally {
            releaseList.complete(Unit)
            client.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun calibrationRevisionReassessesActiveDescriptorsWithoutAnotherNetworkRequest() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        var requests = 0
        var assessments = 0
        var reassessments = 0
        val requestDispatcher = dispatcher
        val engine = object : MockEngine(MockEngineConfig().apply {
            reuseHandlers = true
            addHandler { request ->
                requests++
                respondJson(searchResponse(request.url.parameters["q"].orEmpty(), "org/calibrated"))
            }
        }) {
            override val dispatcher: CoroutineDispatcher = requestDispatcher
        }
        val calibration = MutableCalibrationSource()
        val client = HttpClient(engine)
        try {
            val viewModel = viewModel(
                client = client,
                dispatcher = dispatcher,
                recommendationService = recommendationService(
                    dispatcher = dispatcher,
                    onAssess = { assessments++ },
                    onReassess = { reassessments++ },
                ),
                calibrationSource = calibration,
            )
            viewModel.updateSearchQuery("calibration")
            viewModel.performSearch()
            advanceUntilIdle()
            assertEquals(1, assessments)
            val requestsAfterInitial = requests

            calibration.revisions.value = 1L
            advanceUntilIdle()

            assertEquals(1, assessments)
            assertEquals(1, reassessments)
            assertEquals(requestsAfterInitial, requests)
        } finally {
            client.close()
            Dispatchers.resetMain()
        }
    }

    private fun viewModel(
        client: HttpClient,
        dispatcher: CoroutineDispatcher,
        localModelDao: LocalModelDao = FakeLocalModelDao(),
        storagePathProvider: StoragePathProvider = FakeStoragePathProvider(),
        downloadManager: DownloadManager = DownloadManager(storagePathProvider),
        recommendationService: ModelRecommendationService = recommendationService(dispatcher),
        calibrationSource: CalibrationSource = NoCalibrationSource,
        downloadCoordinator: DownloadCoordinator = observingDownloadCoordinator(ObservingDownloadTaskStore()),
    ): ModelViewModel {
        return ModelViewModel(
            api = huggingFaceApi(client),
            localModelRepository = LocalModelRepository(localModelDao),
            componentRepository = ComponentRepository(FakeDownloadedComponentDao()),
            downloadManager = downloadManager,
            storagePathProvider = storagePathProvider,
            deviceCapabilities = DeviceCapabilities(),
            recommendationService = recommendationService,
            settingsRepository = FakeSettingsRepository(),
            calibrationSource = calibrationSource,
            downloadCoordinator = downloadCoordinator,
        )
    }
}

private class ObservingDownloadTaskStore : DownloadTaskStore {
    val snapshots = MutableStateFlow<List<DownloadBatchSnapshot>>(emptyList())
    val createdRequests = mutableListOf<DownloadBatchRequest>()

    override suspend fun create(request: DownloadBatchRequest, nowEpochMs: Long): String {
        createdRequests += request
        return "created-${createdRequests.size}"
    }
    override fun observeForModel(modelId: String): Flow<List<DownloadBatchSnapshot>> = snapshots
    override suspend fun getBatch(batchId: String): DownloadBatchSnapshot? =
        snapshots.value.singleOrNull { it.batchId == batchId }
    override suspend fun recoverableBatches(): List<DownloadBatchSnapshot> = snapshots.value
    override suspend fun claim(
        artifactId: String,
        owner: String,
        nowEpochMs: Long,
        expiresAtEpochMs: Long,
    ): Boolean = false
    override suspend fun updateProgress(
        artifactId: String,
        bytesReceived: Long,
        entityTag: String?,
        lastModified: String?,
        nowEpochMs: Long,
    ): Boolean = false
    override suspend fun transitionArtifact(
        artifactId: String,
        state: DownloadArtifactState,
        failureCode: DownloadFailureCode?,
        nowEpochMs: Long,
    ): Boolean = false
    override suspend fun setUserIntent(
        batchId: String,
        intent: DownloadUserIntent,
        nowEpochMs: Long,
    ): Boolean = false
    override suspend fun setPlatformTaskId(
        artifactId: String,
        platformTaskId: String?,
        nowEpochMs: Long,
    ): Boolean = false
    override suspend fun releaseLease(
        artifactId: String,
        owner: String,
        nowEpochMs: Long,
    ): Boolean = false
    override suspend fun clearAll() = Unit
}

private fun observingDownloadCoordinator(store: DownloadTaskStore): DownloadCoordinator =
    DownloadCoordinator(
        store = store,
        scheduler = object : PlatformDownloadScheduler {
            override suspend fun enqueue(batchId: String) = Unit
            override suspend fun pause(batchId: String) = Unit
            override suspend fun cancel(batchId: String) = Unit
            override suspend fun reconcile(liveBatchIds: Set<String>) = Unit
        },
        notifications = DownloadNotificationPermissionController {},
        checkpointCleaner = DownloadCheckpointCleaner {},
        nowEpochMs = { 0L },
    )

private fun durableSnapshot(
    batchId: String,
    artifact: DownloadArtifactIdentity,
    artifactState: DownloadArtifactState,
    batchState: DownloadBatchState,
    bytesReceived: Long,
): DownloadBatchSnapshot {
    val request = DownloadArtifactRequest(
        metadata = DownloadMetadataDTO(
            artifact = artifact,
            logicalRole = "model",
            sizeBytes = artifact.expectedBytes,
            author = null,
            libraryName = null,
            pipelineTag = null,
        ),
        primary = true,
    )
    return DownloadBatchSnapshot(
        batchId = batchId,
        ownerModelId = artifact.repositoryId,
        modelType = "image",
        displayName = artifact.relativePath.substringAfterLast('/'),
        state = batchState,
        userIntent = DownloadUserIntent.RUN,
        artifacts = listOf(
            DownloadArtifactSnapshot(
                artifactId = "$batchId-artifact",
                batchId = batchId,
                request = request,
                state = artifactState,
                userIntent = DownloadUserIntent.RUN,
                bytesReceived = bytesReceived,
                expectedBytes = artifact.expectedBytes,
            ),
        ),
        evidence = pendingEvidence(artifact),
    )
}

private fun unknownDiffusionModel(
    storage: StoragePathProvider,
    modelType: String,
): LocalModelEntity = LocalModelEntity(
    modelId = "org/unknown-diffusion",
    filename = DIFFUSERS_BUNDLE_DB_FILENAME,
    localPath = storage.getModelsStorageDirectory("org/unknown-diffusion"),
    sizeBytes = 5L,
    downloadedAt = 0L,
    author = null,
    libraryName = null,
    pipelineTag = "text-to-image",
    modelType = modelType,
    componentStatus = LocalModelEntity.STATUS_READY,
)

private fun MockRequestHandleScope.respondJson(value: String) = respond(
    content = value,
    status = HttpStatusCode.OK,
    headers = headersOf(HttpHeaders.ContentType, "application/json"),
)

private fun searchResponse(query: String, repositoryId: String): String =
    """{"models":[{"_id":"$repositoryId","id":"$repositoryId","private":false}],"modelsCount":1,"q":"$query"}"""

private fun listResponse(repositoryId: String): String =
    """{"models":[{"id":"$repositoryId","private":false}],"numItemsPerPage":1,"numTotalItems":1,"pageIndex":0}"""

private fun writeVerifiedBundle(
    storage: StoragePathProvider,
    repositoryId: String,
    revision: String,
    files: List<Triple<String, String, ByteArray>>,
) {
    val identities = files.map { (path, _, bytes) ->
        requireNotNull(
            DownloadArtifactIdentity.create(
                repositoryId = repositoryId,
                immutableRevision = revision,
                relativePath = path,
                remoteObjectId = "sha256:${bytes.sha256()}",
                expectedBytes = bytes.size.toLong(),
            ),
        )
    }
    val bundleId = requireNotNull(artifactBundleId(identities))
    val root = File(storage.getModelsStorageDirectory(repositoryId)).apply { mkdirs() }
    val entries = files.zip(identities).map { (fixture, identity) ->
        val (path, role, bytes) = fixture
        File(root, path).apply {
            parentFile?.mkdirs()
            writeBytes(bytes)
        }
        requireNotNull(
            ArtifactManifestEntry.create(
                logicalRole = role,
                identity = identity,
                byteCount = bytes.size.toLong(),
                contentSha256 = bytes.sha256(),
                bundleId = bundleId,
                localRelativePath = path,
            ),
        )
    }
    val encoded = Json { encodeDefaults = true }.encodeToString(requireNotNull(ArtifactManifest.create(entries)))
    File(root, ArtifactManifestStore.MANIFEST_FILE_NAME).writeText(encoded)
    File(root, ArtifactBundleManifestStore.MANIFEST_FILE_NAME).writeText(encoded)
}

private fun ByteArray.sha256(): String =
    MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }

private fun huggingFaceApi(client: HttpClient): HuggingFaceApi {
    val repository = HuggingFaceRepository(
        RemoteHuggingFaceApiService(client, Json { ignoreUnknownKeys = true }, "https://huggingface.co"),
    )
    return object : HuggingFaceApi {
        override val listModels = ListModelsUseCase(repository)
        override val listRecommendationModels = ListRecommendationModelsUseCase(repository)
        override val searchModels = SearchModelsUseCase(repository)
        override val getModelDetail = GetModelDetailUseCase(repository)
        override val getRecommendationModelDetail = GetRecommendationModelDetailUseCase(repository)
        override val getModelFileTree = GetModelFileTreeUseCase(repository)
        override val getModelConfig = GetModelConfigUseCase(repository)
    }
}

private fun exactDetailClient(
    dispatcher: CoroutineDispatcher,
    repositoryId: String,
    revision: String,
    path: String,
    objectId: String,
): HttpClient {
    val engine = object : MockEngine(MockEngineConfig().apply {
        reuseHandlers = true
        addHandler { request ->
            when {
                request.url.encodedPath.contains("/tree/") -> respondJson(
                    """[{"path":"$path","type":"file","size":100,"lfs":{"oid":"$objectId","size":100}}]""",
                )
                request.url.encodedPath.endsWith("/$repositoryId") -> respondJson(
                    """{"id":"$repositoryId","modelId":"$repositoryId","sha":"$revision","private":false}""",
                )
                else -> error("Unexpected request ${request.url}")
            }
        }
    }) {
        override val dispatcher: CoroutineDispatcher = dispatcher
    }
    return HttpClient(engine)
}

private fun metadata(artifact: DownloadArtifactIdentity) = DownloadMetadataDTO(
    artifact = artifact,
    logicalRole = "model",
    sizeBytes = artifact.expectedBytes,
    author = null,
    libraryName = null,
    pipelineTag = null,
)

private fun modelFileIdentity(
    repositoryId: String,
    revision: String,
    path: String,
    objectId: String,
) = ModelFileIdentity(
    repositoryId = repositoryId,
    revision = revision,
    path = path,
    sizeBytes = 100L,
    gitOid = null,
    lfsOid = objectId,
    xetHash = null,
    evidence = emptyList(),
)

private fun recommendationService(
    dispatcher: CoroutineDispatcher,
    onAssess: () -> Unit = {},
    onReassess: () -> Unit = {},
    descriptorFiles: List<ModelFileIdentity>? = null,
    descriptor: ModelDescriptor? = null,
    recommendationCategory: RecommendationCategory = RecommendationCategory.RECOMMENDED,
    recommendationStorageFit: FitBand? = null,
    variantSet: ((String) -> RepositoryVariantSet)? = null,
) = ModelRecommendationService(
    metadataSource = ModelMetadataSource { repositoryId, _ ->
        variantSet?.invoke(repositoryId)?.let { return@ModelMetadataSource it }
        val identities = descriptorFiles ?: listOf(
            ModelFileIdentity(
                repositoryId = repositoryId,
                revision = "a".repeat(40),
                path = "model-Q4_K_M.gguf",
                sizeBytes = 100L,
                gitOid = "oid-$repositoryId",
                lfsOid = null,
                xetHash = null,
                evidence = emptyList(),
            ),
        )
        val identity = identities.first()
        RepositoryVariantSet.Ready(
            listOf(
                RepositoryVariant(
                    descriptor = descriptor ?: LlmModelDescriptor(
                        repositoryId = repositoryId,
                        revision = identity.revision,
                        files = identities,
                        architecture = "llama",
                        quantization = QuantizationEvidence.Known("Q4_K_M"),
                        parameterCount = 1_000_000L,
                        contextLimit = 4_096,
                        transformerShape = null,
                        ggufVersion = 3,
                        requiredEngineFeatures = emptySet(),
                        evidence = emptyList(),
                    ),
                    displayName = identity.path,
                ),
            ),
        )
    },
    snapshotSource = object : RecommendationSnapshotSource {
        override suspend fun captureInitial(): DeviceSnapshot = recommendationSnapshot()
        override suspend fun refreshResources(previous: DeviceSnapshot): DeviceSnapshot = previous
    },
    variantEvaluator = object : RecommendationVariantEvaluator {
        override suspend fun assess(
            descriptor: ModelDescriptor,
            snapshot: DeviceSnapshot,
            workload: WorkloadConfig,
        ): ModelAssessment = recommendationAssessment(descriptor.repositoryId)
            .also { onAssess() }

        override fun rebuild(assessment: ModelAssessment, snapshot: DeviceSnapshot): ModelAssessment = assessment

        override suspend fun reassess(
            descriptor: ModelDescriptor,
            previous: ModelAssessment,
            snapshot: DeviceSnapshot,
            workload: WorkloadConfig,
        ): ModelAssessment = previous.also { onReassess() }

        override fun personalize(
            descriptor: ModelDescriptor,
            assessment: ModelAssessment,
            snapshot: DeviceSnapshot,
            profile: RecommendationProfile,
        ) = PersonalizedRecommendation(
            assessmentKey = assessment.assessmentKey,
            category = recommendationCategory,
            selectedPlan = null,
            reasons = listOf(AssessmentReason.METADATA_VALIDATED),
            profile = profile,
            storageFit = recommendationStorageFit,
        )

        override fun sortKey(
            descriptor: ModelDescriptor,
            assessment: ModelAssessment,
            snapshot: DeviceSnapshot,
            profile: RecommendationProfile,
        ) = RecommendationSortKey.create(
            category = RecommendationCategory.RECOMMENDED,
            confidence = assessment.confidence,
            utility = 1.0,
            worstNormalizedHeadroom = 1.0,
            stableId = descriptor.repositoryId,
        )
    },
    evaluationDispatcher = dispatcher,
    clock = { 1_000L },
)

private class MutableCalibrationSource : CalibrationSource {
    val revisions = MutableStateFlow(0L)

    override fun engineVersion(): String? = null
    override fun backendProfileFor(backend: com.debanshu777.caraml.core.platform.BackendKind): BackendPerformanceProfile? = null
    override fun correctionFor(key: CalibrationKey): CalibrationCorrection? = null
    override fun revision(): Long = revisions.value
    override fun revisionUpdates(): Flow<Long> = revisions
}

private fun recommendationAssessment(key: String) = ModelAssessment(
    assessmentKey = key,
    compatibility = Compatibility.Compatible,
    planAssessments = AssessedPlans(emptyList(), assessmentKey = key),
    baseHostBudgetBytes = 1_000_000L,
    baseGpuBudgetBytes = null,
    baseSharedBudgetBytes = null,
    baseStorageBudgetBytes = 1_000_000L,
    confidence = AssessmentConfidence(
        compatibility = Confidence.HIGH,
        memory = Confidence.HIGH,
        storage = Confidence.HIGH,
        performance = Confidence.HIGH,
    ),
    evidence = emptyList(),
)

private fun recommendationSnapshot(): DeviceSnapshot {
    val hardware = HardwareProfile(
        cpuArchitecture = "arm64",
        logicalCoreCount = 8,
        performanceCoreCount = 4,
        instructionSets = emptySet(),
        backends = emptyList(),
        memoryTopology = MemoryTopology.UNKNOWN,
        evidence = emptyList(),
    )
    val resources = ResourceSnapshot(
        additionalAllocatableHostBytes = 2_000_000L,
        additionalAllocatableGpuBytes = null,
        currentProcessBytes = 100_000L,
        freeStorageBytes = 2_000_000L,
        osPressureReserveHostBytes = 0L,
        observedAppFootprintNoiseP95Bytes = 0L,
        platformMinimumReserveHostBytes = 1L,
        lowMemory = false,
        thermalState = ThermalState.NOMINAL,
        powerPolicyState = PowerPolicyState.NORMAL,
        capturedAtEpochMs = 1_000L,
        evidence = emptyList(),
        confidence = ResourcePoolConfidence(host = Confidence.HIGH, storage = Confidence.HIGH),
    )
    return DeviceSnapshot(
        hardwareProfile = hardware,
        resources = resources,
        baseHostBudgetBytes = 1_000_000L,
        baseGpuBudgetBytes = null,
        baseSharedBudgetBytes = null,
        baseStorageBudgetBytes = 1_000_000L,
        isFresh = true,
        evidence = emptyList(),
        budgetConfidence = ResourcePoolConfidence(host = Confidence.HIGH, storage = Confidence.HIGH),
    )
}

private class FakeSettingsRepository : SettingsRepository {
    private val settings = MutableStateFlow(AppSettings())

    override fun getSettings(): Flow<AppSettings> = settings
    override suspend fun updateSettings(settings: AppSettings) {
        this.settings.value = settings
    }

    override suspend fun updateRecommendationProfile(profile: RecommendationProfile) = Unit
    override suspend fun completeModelProfileOnboarding(profile: RecommendationProfile) = Unit
}

private class FakeStoragePathProvider(
    private val baseDirectory: File = File("/tmp/caraml-test"),
    private val realFileAccess: Boolean = false,
    private val availableStorageBytes: Long = 1_000_000L,
) : StoragePathProvider {
    override fun getModelsStorageDirectory(modelId: String): String = File(baseDirectory, modelId).path
    override fun getDatabasePath(): String = "/tmp/caraml-test.db"
    override fun fileExists(path: String): Boolean = realFileAccess && File(path).isFile
    override fun getAvailableStorageBytes(): Long = availableStorageBytes
    override fun getTotalStorageBytes(): Long = maxOf(availableStorageBytes, 2_000_000L)
    override fun isModelFileReadable(path: String): Boolean = realFileAccess && File(path).isFile && File(path).canRead()
    override fun isDirectoryReadable(path: String): Boolean = realFileAccess && File(path).isDirectory && File(path).canRead()
    override fun getFileSize(path: String): Long = if (realFileAccess) File(path).length() else 0L
    override fun renameFile(from: String, to: String): Boolean = false
    override fun deleteDownloadedModelContent(modelId: String, localPath: String): Boolean = false
}

private class FakeLocalModelDao(
    private val filenames: List<String> = emptyList(),
    private val mainModels: List<LocalModelEntity> = emptyList(),
) : LocalModelDao {
    val updatedStatuses = mutableMapOf<String, String>()
    var filenameQueryCount: Int = 0
        private set
    override suspend fun getFilenamesByModelId(modelId: String): List<String> {
        filenameQueryCount += 1
        return filenames
    }
    override suspend fun deleteByModelIdAndFilename(modelId: String, filename: String) = Unit
    override suspend fun deleteAllForModelId(modelId: String) = Unit
    override suspend fun insert(entity: LocalModelEntity) = Unit
    override fun getAllDownloadedFiles(): Flow<List<LocalModelEntity>> = flowOf(emptyList())
    override fun getDownloadedFilesByType(modelType: String): Flow<List<LocalModelEntity>> = flowOf(emptyList())
    override suspend fun incrementUsageCount(modelId: String, filename: String) = Unit
    override fun getTotalDownloadedSizeBytes(): Flow<Long> = flowOf(0L)
    override suspend fun updateComponentStatus(modelId: String, status: String) {
        updatedStatuses[modelId] = status
    }
    override suspend fun getMainModels(): List<LocalModelEntity> = mainModels
    override suspend fun updateArch(modelId: String, arch: String) = Unit
    override suspend fun demoteMmprojFilesFromMain() = Unit
}

private class FakeDownloadedComponentDao : DownloadedComponentDao {
    override suspend fun insertComponent(entity: DownloadedComponentEntity): Long = 1L
    override suspend fun insertLink(entity: ModelComponentLinkEntity) = Unit
    override suspend fun getByRepoAndPath(repoId: String, filePath: String): DownloadedComponentEntity? = null
    override suspend fun isComponentDownloaded(repoId: String, filePath: String): Boolean = false
    override fun getAllComponents(): Flow<List<DownloadedComponentEntity>> = flowOf(emptyList())
    override suspend fun getComponentsForModel(modelId: String): List<DownloadedComponentEntity> = emptyList()
    override suspend fun deleteByRepoAndPath(repoId: String, filePath: String) = Unit
}
