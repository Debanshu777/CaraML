package com.debanshu777.caraml.features.modelhub.presentation.search

import com.debanshu777.caraml.core.data.settings.SettingsRepository
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
import com.debanshu777.caraml.features.modelhub.domain.RecommendationSnapshotSource
import com.debanshu777.caraml.features.modelhub.domain.RecommendationVariantEvaluator
import com.debanshu777.caraml.features.modelhub.domain.RepositoryVariant
import com.debanshu777.caraml.features.modelhub.domain.RepositoryVariantSet
import com.debanshu777.huggingfacemanager.HuggingFaceApi
import com.debanshu777.huggingfacemanager.api.RemoteHuggingFaceApiService
import com.debanshu777.huggingfacemanager.download.DownloadManager
import com.debanshu777.huggingfacemanager.download.DownloadArtifactIdentity
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ModelViewModelRecommendationTest {
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
        recommendationService: ModelRecommendationService = recommendationService(dispatcher),
        calibrationSource: CalibrationSource = NoCalibrationSource,
    ): ModelViewModel {
        return ModelViewModel(
            api = huggingFaceApi(client),
            localModelRepository = LocalModelRepository(localModelDao),
            componentRepository = ComponentRepository(FakeDownloadedComponentDao()),
            downloadManager = DownloadManager(storagePathProvider),
            storagePathProvider = storagePathProvider,
            deviceCapabilities = DeviceCapabilities(),
            recommendationService = recommendationService,
            settingsRepository = FakeSettingsRepository(),
            calibrationSource = calibrationSource,
        )
    }
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

private fun recommendationService(
    dispatcher: CoroutineDispatcher,
    onAssess: () -> Unit = {},
    onReassess: () -> Unit = {},
) = ModelRecommendationService(
    metadataSource = ModelMetadataSource { repositoryId, _ ->
        val identity = ModelFileIdentity(
            repositoryId = repositoryId,
            revision = "a".repeat(40),
            path = "model-Q4_K_M.gguf",
            sizeBytes = 100L,
            gitOid = "oid-$repositoryId",
            lfsOid = null,
            xetHash = null,
            evidence = emptyList(),
        )
        RepositoryVariantSet.Ready(
            listOf(
                RepositoryVariant(
                    descriptor = LlmModelDescriptor(
                        repositoryId = repositoryId,
                        revision = identity.revision,
                        file = identity,
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
            category = RecommendationCategory.RECOMMENDED,
            selectedPlan = null,
            reasons = listOf(AssessmentReason.METADATA_VALIDATED),
            profile = profile,
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
) : StoragePathProvider {
    override fun getModelsStorageDirectory(modelId: String): String = File(baseDirectory, modelId).path
    override fun getDatabasePath(): String = "/tmp/caraml-test.db"
    override fun fileExists(path: String): Boolean = realFileAccess && File(path).isFile
    override fun getAvailableStorageBytes(): Long = 1_000_000L
    override fun getTotalStorageBytes(): Long = 2_000_000L
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
    override suspend fun getFilenamesByModelId(modelId: String): List<String> = filenames
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
