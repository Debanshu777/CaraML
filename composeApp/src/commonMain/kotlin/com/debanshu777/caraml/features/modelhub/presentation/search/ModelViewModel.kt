package com.debanshu777.caraml.features.modelhub.presentation.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.LoadState
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.asItemSnapshotListFlow
import com.debanshu777.caraml.core.data.settings.SettingsRepository
import com.debanshu777.caraml.core.data.inference.NATIVE_DIFFUSERS_CONSUMED_PATHS
import com.debanshu777.caraml.core.recommendation.InstalledModelWorkloadFactory
import com.debanshu777.caraml.core.recommendation.LlmModelDescriptor
import com.debanshu777.caraml.core.recommendation.DiffusionModelDescriptor
import com.debanshu777.caraml.core.recommendation.ModelDescriptor
import com.debanshu777.caraml.core.recommendation.ModelFileIdentity
import com.debanshu777.caraml.core.recommendation.DescriptorLimits
import com.debanshu777.caraml.core.recommendation.canonicalDownloadRemoteObjectId
import com.debanshu777.caraml.core.recommendation.CalibrationRunResult
import com.debanshu777.caraml.core.recommendation.CalibrationSource
import com.debanshu777.caraml.core.recommendation.NoCalibrationSource
import com.debanshu777.caraml.core.recommendation.QuickCalibrationRunner
import com.debanshu777.caraml.core.recommendation.WorkloadConfig
import com.debanshu777.caraml.core.storage.component.ComponentRepository
import com.debanshu777.caraml.core.storage.localmodel.LocalModelRepository
import com.debanshu777.caraml.core.storage.localmodel.ModelType
import com.debanshu777.caraml.core.platform.DeviceCapabilities
import com.debanshu777.caraml.core.platform.DeviceHints
import com.debanshu777.caraml.core.platform.HardwareProfile
import com.debanshu777.caraml.core.platform.ResourceSnapshot
import com.debanshu777.caraml.core.rating.parseSizeHintToBytes
import com.debanshu777.caraml.core.download.DownloadArtifactRequest
import com.debanshu777.caraml.core.download.DownloadArtifactSnapshot
import com.debanshu777.caraml.core.download.DownloadArtifactState
import com.debanshu777.caraml.core.download.DownloadBatchRequest
import com.debanshu777.caraml.core.download.DownloadBatchSnapshot
import com.debanshu777.caraml.core.download.DownloadBatchState
import com.debanshu777.caraml.core.download.DownloadUserIntent
import com.debanshu777.caraml.core.download.DownloadCoordinator
import com.debanshu777.caraml.core.download.DownloadEvidenceFactory
import com.debanshu777.caraml.core.download.downloadArtifactTaskId
import com.debanshu777.caraml.core.recommendation.storage.PersistedModelEvidenceCodec
import com.debanshu777.caraml.features.modelhub.domain.ModelRecommendationService
import com.debanshu777.caraml.features.modelhub.domain.RecommendationOrdering
import com.debanshu777.caraml.features.modelhub.domain.RecommendationQuerySession
import com.debanshu777.caraml.features.modelhub.domain.RecommendedModelUiState
import com.debanshu777.caraml.features.modelhub.domain.matchesExactBrowseGroup
import com.debanshu777.caraml.features.modelhub.domain.QuerySupersededCancellationException
import com.debanshu777.caraml.features.modelhub.domain.DownloadAdmission
import com.debanshu777.caraml.features.modelhub.domain.DownloadAdmissionPolicy
import com.debanshu777.caraml.features.modelhub.domain.DownloadAdmissionRejected
import com.debanshu777.caraml.features.modelhub.domain.DescriptorState
import com.debanshu777.caraml.features.modelhub.domain.downloadAdmissionErrorMessage
import com.debanshu777.caraml.features.modelhub.domain.ArtifactStorageKey
import com.debanshu777.caraml.features.modelhub.domain.ArtifactStorageLocation
import com.debanshu777.caraml.features.modelhub.domain.DownloadStorageEstimator
import com.debanshu777.caraml.features.modelhub.domain.DownloadStorageLayout
import com.debanshu777.caraml.features.modelhub.domain.LocalDownloadArtifact
import com.debanshu777.caraml.features.modelhub.domain.LocalDownloadInventory
import com.debanshu777.caraml.features.modelhub.domain.StorageRequirement
import com.debanshu777.caraml.features.modelhub.domain.StorageVolume
import com.debanshu777.huggingfacemanager.HuggingFaceApi
import com.debanshu777.huggingfacemanager.api.ListModelsParams
import com.debanshu777.huggingfacemanager.api.ModelPageRequest
import com.debanshu777.huggingfacemanager.api.ModelPageCursor
import com.debanshu777.huggingfacemanager.api.error.DataError
import com.debanshu777.huggingfacemanager.api.error.Result
import com.debanshu777.huggingfacemanager.download.DownloadManager
import com.debanshu777.huggingfacemanager.download.DownloadArtifactIdentity
import com.debanshu777.huggingfacemanager.download.DownloadMetadataDTO
import com.debanshu777.huggingfacemanager.download.ArtifactVerificationException
import com.debanshu777.huggingfacemanager.download.ArtifactFileAccessException
import com.debanshu777.huggingfacemanager.download.ArtifactManifest
import com.debanshu777.huggingfacemanager.download.ArtifactManifestEntry
import com.debanshu777.huggingfacemanager.download.artifactBundleId
import com.debanshu777.huggingfacemanager.download.immutableArtifactStorageLocation
import com.debanshu777.huggingfacemanager.download.IncompleteDownloadException
import com.debanshu777.huggingfacemanager.download.InsufficientStorageException
import com.debanshu777.huggingfacemanager.download.StoragePathProvider
import com.debanshu777.huggingfacemanager.model.ModelDetailResponse
import com.debanshu777.huggingfacemanager.model.ModelFileWeightFilter
import com.debanshu777.huggingfacemanager.model.normalizedDiffusersRelativePath
import com.debanshu777.huggingfacemanager.model.ModelSort
import com.debanshu777.huggingfacemanager.model.ParameterRange
import com.debanshu777.huggingfacemanager.model.ListModelsResponse
import com.debanshu777.huggingfacemanager.model.SearchModelsResponse
import com.debanshu777.huggingfacemanager.sdcpp.loadSdCppCuratedCatalog
import com.debanshu777.huggingfacemanager.sdcpp.toListModelsResponse
import com.debanshu777.huggingfacemanager.sdcpp.toVideoListModelsResponse
import com.debanshu777.huggingfacemanager.sdcpp.getModelSetup
import com.debanshu777.huggingfacemanager.sdcpp.ComponentRole
import com.debanshu777.huggingfacemanager.sdcpp.SdCppComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.math.round

private const val MODELS_VOLUME = "models"
private const val MAX_LOADED_MODELS = 256
private val SUPPORTED_REMOTE_SORTS = setOf(
    ModelSort.TRENDING, ModelSort.DOWNLOADS, ModelSort.LIKES, ModelSort.CREATED, ModelSort.MODIFIED,
)

internal data class RelevantDownloadTask(
    val batch: DownloadBatchSnapshot,
    val task: DownloadArtifactSnapshot,
)

data class DurableDownloadControlUiState(
    val batchId: String,
    val artifactId: String,
    val batchState: DownloadBatchState,
    val artifactState: DownloadArtifactState,
)

private fun RelevantDownloadTask.toControlUiState(): DurableDownloadControlUiState =
    DurableDownloadControlUiState(
        batchId = batch.batchId,
        artifactId = task.artifactId,
        batchState = batch.state,
        artifactState = task.state,
    )

internal fun relevantDownloadTask(
    batches: List<DownloadBatchSnapshot>,
    artifact: DownloadArtifactIdentity?,
): RelevantDownloadTask? {
    if (artifact == null) return null
    return relevantDownloadTask(batches) { task ->
        task.request.metadata.artifact == artifact
    }
}

internal fun relevantDownloadControl(
    batches: List<DownloadBatchSnapshot>,
    expectedRequests: List<DownloadArtifactRequest>,
    artifact: DownloadArtifactIdentity?,
): DurableDownloadControlUiState? =
    relevantDownloadTask(batches, expectedRequests, artifact)?.toControlUiState()

internal fun relevantDownloadTask(
    batches: List<DownloadBatchSnapshot>,
    expectedRequests: List<DownloadArtifactRequest>,
    artifact: DownloadArtifactIdentity?,
): RelevantDownloadTask? {
    if (artifact == null || expectedRequests.isEmpty()) return null
    val expectedTaskId = expectedRequests.singleOrNull { request ->
        request.metadata.artifact == artifact
    }?.let(::downloadArtifactTaskId) ?: return null
    return relevantDownloadTask(
        batches = batches.filter { batch -> batch.matchesExactRequests(expectedRequests) },
    ) { task ->
        downloadArtifactTaskId(task.request) == expectedTaskId
    }
}

private fun DownloadBatchSnapshot.matchesExactRequests(
    expectedRequests: List<DownloadArtifactRequest>,
): Boolean {
    if (artifacts.size != expectedRequests.size) return false
    val expectedTaskIds = expectedRequests.map(::downloadArtifactTaskId)
    val actualTaskIds = artifacts.map { artifact -> downloadArtifactTaskId(artifact.request) }
    if (expectedTaskIds.distinct().size != expectedTaskIds.size ||
        actualTaskIds.distinct().size != actualTaskIds.size
    ) {
        return false
    }
    return expectedTaskIds.sorted() == actualTaskIds.sorted()
}

private fun relevantDownloadTask(
    batches: List<DownloadBatchSnapshot>,
    matches: (DownloadArtifactSnapshot) -> Boolean,
): RelevantDownloadTask? = batches.asSequence()
    .flatMap { batch ->
        batch.artifacts.asSequence().map { task -> RelevantDownloadTask(batch, task) }
    }
    .filter { selection -> matches(selection.task) }
    .minWithOrNull(
        compareBy<RelevantDownloadTask>(
            { downloadArtifactProjectionPriority(it.task.state) },
            { it.batch.batchId },
            { it.task.artifactId },
        ),
    )

private fun relevantDownloadBatch(
    batches: List<DownloadBatchSnapshot>,
): DownloadBatchSnapshot? = batches.minWithOrNull(
    compareBy<DownloadBatchSnapshot>(
        { downloadBatchProjectionPriority(it.state) },
        DownloadBatchSnapshot::batchId,
    ),
)

private fun downloadArtifactProjectionPriority(state: DownloadArtifactState): Int = when (state) {
    DownloadArtifactState.RUNNING -> 0
    DownloadArtifactState.VERIFYING -> 1
    DownloadArtifactState.QUEUED -> 2
    DownloadArtifactState.WAITING_FOR_NETWORK -> 3
    DownloadArtifactState.PAUSED -> 4
    DownloadArtifactState.FAILED_RETRYABLE -> 5
    DownloadArtifactState.COMPLETED -> 6
    DownloadArtifactState.FAILED_TERMINAL -> 7
    DownloadArtifactState.CANCELLED -> 8
}

private fun downloadBatchProjectionPriority(state: DownloadBatchState): Int = when (state) {
    DownloadBatchState.RUNNING -> 0
    DownloadBatchState.VERIFYING -> 1
    DownloadBatchState.QUEUED -> 2
    DownloadBatchState.WAITING_FOR_NETWORK -> 3
    DownloadBatchState.PAUSED -> 4
    DownloadBatchState.FAILED_RETRYABLE -> 5
    DownloadBatchState.COMPLETED -> 6
    DownloadBatchState.FAILED_TERMINAL -> 7
    DownloadBatchState.CANCELLED -> 8
}

data class GgufFileUiState(
    val path: String,
    val filename: String,
    val sizeBytes: Long?,
    val isDownloaded: Boolean,
    val progress: Float?,
    val artifact: DownloadArtifactIdentity? = null,
    val durableControl: DurableDownloadControlUiState? = null,
)

internal fun projectCommittedDiffusionVariants(
    variants: List<GgufFileUiState>,
    manifest: ArtifactManifest?,
): List<GgufFileUiState> {
    val committed = manifest?.entries?.map { it.identity }?.toSet().orEmpty()
    return variants.map { file ->
        file.copy(isDownloaded = file.artifact in committed, progress = null)
    }
}

internal fun ArtifactManifest.matchesExactBundle(metadata: List<DownloadMetadataDTO>): Boolean =
    entries.size == metadata.size && metadata.isNotEmpty() && metadata.all { expected ->
        entries.singleOrNull { installed ->
            installed.logicalRole == expected.logicalRole &&
                installed.identity == expected.artifact &&
                installed.bundleId == expected.bundleId &&
                installed.localRelativePath == expected.destinationRelativePath &&
                installed.layoutRelativePath == expected.layoutRelativePath
        } != null
    }

private val diffusionDirectoryRoleByPath = mapOf(
    "unet/diffusion_pytorch_model.safetensors" to "model",
    "vae/diffusion_pytorch_model.safetensors" to "diffusers-vae",
    "text_encoder/model.safetensors" to "diffusers-clip-l",
    "text_encoder_2/model.safetensors" to "diffusers-clip-g",
)

internal fun buildDeterministicDiffusionBundleMetadata(
    selected: List<GgufFileUiState>,
    componentMetadata: Collection<DownloadMetadataDTO>,
    author: String?,
    libraryName: String?,
    pipelineTag: String?,
): List<DownloadMetadataDTO> {
    if (selected.isEmpty()) return emptyList()
    val selectedArtifacts = selected.map { file ->
        val artifact = file.artifact ?: return emptyList()
        file to artifact
    }
    if (selectedArtifacts.map { it.second }.toSet().size != selectedArtifacts.size) return emptyList()

    val selectedDestinations = selectedArtifacts.associate { (file, _) ->
        file to normalizedDiffusersRelativePath(file.path)
    }
    val directoryBundle = selectedArtifacts.size > 1 ||
        selectedDestinations.values.any { it in NATIVE_DIFFUSERS_CONSUMED_PATHS }
    val primary = if (directoryBundle) {
        if (selectedDestinations.values.toSet() != NATIVE_DIFFUSERS_CONSUMED_PATHS) return emptyList()
        selectedArtifacts.singleOrNull { (file) ->
            selectedDestinations.getValue(file) == "unet/diffusion_pytorch_model.safetensors"
        } ?: return emptyList()
    } else {
        selectedArtifacts.single()
    }

    val componentByIdentity = componentMetadata.associateBy { it.artifact }
    if (componentByIdentity.size != componentMetadata.size) return emptyList()
    val identities = (selectedArtifacts.map { it.second } + componentMetadata.map { it.artifact }).distinct()
    val bundleId = artifactBundleId(identities) ?: return emptyList()
    val selectedMetadata = selectedArtifacts.map { (file, artifact) ->
        val destination = selectedDestinations.getValue(file)
        val component = componentByIdentity[artifact]
        val role = when {
            artifact == primary.second -> "model"
            component != null -> component.logicalRole
            else -> diffusionDirectoryRoleByPath[destination] ?: return emptyList()
        }
        DownloadMetadataDTO(
            artifact = artifact,
            logicalRole = role,
            sizeBytes = artifact.expectedBytes,
            author = author,
            libraryName = libraryName,
            pipelineTag = pipelineTag,
            contextLength = null,
            bundleId = bundleId,
            destinationRelativePath = immutableArtifactStorageLocation(artifact, bundleId).localRelativePath,
        )
    }
    val selectedIdentities = selectedMetadata.mapTo(mutableSetOf()) { it.artifact }
    val externalComponents = componentMetadata
        .filterNot { it.artifact in selectedIdentities }
        .map { component ->
            component.copy(
                bundleId = bundleId,
                destinationRelativePath = immutableArtifactStorageLocation(
                    component.artifact,
                    bundleId,
                ).localRelativePath,
            )
        }
    return (selectedMetadata + externalComponents).sortedWith(
        compareBy<DownloadMetadataDTO>(
            { if (it.logicalRole == "model") 0 else 1 },
            { it.logicalRole },
            { it.artifact.repositoryId },
            { it.artifact.relativePath },
            { it.destinationRelativePath },
        ),
    )
}

internal data class InterruptedDiffusionBundleRecovery(
    val metadata: List<DownloadMetadataDTO>,
    val installedMetadata: List<DownloadMetadataDTO>,
)

private fun ArtifactManifestEntry.matchesExact(metadata: DownloadMetadataDTO): Boolean =
    logicalRole == metadata.logicalRole && identity == metadata.artifact &&
        bundleId == metadata.bundleId && localRelativePath == metadata.destinationRelativePath &&
        layoutRelativePath == metadata.layoutRelativePath

internal fun findInstalledSetupComponent(
    entries: List<ArtifactManifestEntry>,
    component: SdCppComponent,
): ArtifactManifestEntry? {
    val layout = normalizedDiffusersRelativePath(component.filePath)
    return entries.singleOrNull { entry ->
        entry.logicalRole == component.role.name.lowercase() &&
            entry.identity.repositoryId == component.repoId &&
            entry.identity.relativePath == component.filePath &&
            entry.layoutRelativePath == layout
    }
}

internal fun recoverInterruptedDiffusionBundle(
    candidates: List<List<DownloadMetadataDTO>>,
    installedEntries: List<ArtifactManifestEntry>,
): InterruptedDiffusionBundleRecovery? {
    val recoveries = candidates.asSequence()
        .filter { it.isNotEmpty() && it.map(DownloadMetadataDTO::bundleId).toSet().size == 1 }
        .distinctBy { it.first().bundleId }
        .map { metadata ->
            InterruptedDiffusionBundleRecovery(
                metadata = metadata,
                installedMetadata = metadata.filter { expected ->
                    installedEntries.any { it.matchesExact(expected) }
                },
            )
        }
        .filter { it.installedMetadata.isNotEmpty() }
        .toList()
    val bestCount = recoveries.maxOfOrNull { it.installedMetadata.size } ?: return null
    return recoveries.filter { it.installedMetadata.size == bestCount }.singleOrNull()
}

data class StorageInfoUiState(
    val totalDeviceBytes: Long = 0L,
    val availableDeviceBytes: Long = 0L,
    val usedByModelsBytes: Long = 0L,
    val deviceHints: DeviceHints? = null,
    val hardwareProfile: HardwareProfile? = null,
    val resourceSnapshot: ResourceSnapshot? = null,
    val hasSampled: Boolean = false,
)

data class QuickCalibrationUiState(
    val running: Boolean = false,
    val result: CalibrationRunResult? = null,
)

data class SetupComponentUiState(
    val role: ComponentRole,
    val repoId: String,
    val filePath: String,
    val sizeHint: String?,
    val isDownloaded: Boolean,
    val progress: Float?,
    val required: Boolean,
    /** Non-null when this component is already on disk from another model download. */
    val sharedFrom: String? = null,
)

/**
 * Unified state for the Smart Install card on the model detail page.
 * Combines main model variants, required components, and install progress.
 */
data class InstallBundleUiState(
    val variants: List<GgufFileUiState> = emptyList(),
    val selectedVariantPath: String? = null,
    val components: List<SetupComponentUiState> = emptyList(),
    /** Total bytes that will be newly downloaded (skips already-downloaded items). */
    val totalNewDownloadBytes: Long = 0L,
    val isInstalling: Boolean = false,
    val installError: String? = null,
    /** True when main model weight + all required components are present on disk. */
    val isReady: Boolean = false,
    /** True for self-contained models that need no extra component downloads. */
    val isSelfContained: Boolean = true,
    /** 0..1 fraction for overall install progress; null when size is unknown. */
    val overallProgress: Float? = null,
    /** Cumulative bytes received across all files in the current install. */
    val overallBytesReceived: Long = 0L,
    /** Total bytes to download for the current install. */
    val overallBytesTotal: Long = 0L,
    /** Short filename of the file currently being downloaded, e.g. "flux1-dev-q4_k.gguf". */
    val currentDownloadLabel: String? = null,
    /** Exact current batch/task pair authorized for user controls. */
    val durableControl: DurableDownloadControlUiState? = null,
)

/** Internal snapshot of ongoing install progress, updated on every progress tick. */
private data class InstallProgress(
    val fraction: Float? = null,
    val bytesReceived: Long = 0L,
    val bytesTotal: Long = 0L,
    val label: String? = null,
)

private enum class DownloadControlCommand {
    PAUSE,
    RESUME,
    CANCEL,
    RETRY,
    ;

    fun accepts(state: DownloadBatchState, intent: DownloadUserIntent): Boolean = when (this) {
        PAUSE -> state in setOf(
            DownloadBatchState.QUEUED,
            DownloadBatchState.RUNNING,
            DownloadBatchState.WAITING_FOR_NETWORK,
        )
        RESUME -> state == DownloadBatchState.PAUSED ||
            (state == DownloadBatchState.VERIFYING && intent == DownloadUserIntent.PAUSE)
        CANCEL -> state in setOf(
            DownloadBatchState.QUEUED,
            DownloadBatchState.RUNNING,
            DownloadBatchState.PAUSED,
            DownloadBatchState.WAITING_FOR_NETWORK,
            DownloadBatchState.FAILED_RETRYABLE,
        )
        RETRY -> state == DownloadBatchState.FAILED_RETRYABLE
    }
}

private sealed interface PendingDownloadForLater {
    data class Single(
        val modelId: String,
        val path: String,
        val metadata: DownloadMetadataDTO,
    ) : PendingDownloadForLater

    data class Smart(val modelId: String, val variantPath: String) : PendingDownloadForLater

    data class Group(val modelId: String, val metadata: List<DownloadMetadataDTO>) : PendingDownloadForLater
}

private val modelWorkloadFactory = InstalledModelWorkloadFactory()

private fun defaultRecommendationWorkload(mode: ModelHubBrowseMode): WorkloadConfig =
    modelWorkloadFactory.createForBrowse(mode)

class ModelViewModel(
    private val api: HuggingFaceApi,
    private val localModelRepository: LocalModelRepository,
    private val componentRepository: ComponentRepository,
    private val downloadManager: DownloadManager,
    private val storagePathProvider: StoragePathProvider,
    private val deviceCapabilities: DeviceCapabilities,
    private val recommendationService: ModelRecommendationService,
    private val settingsRepository: SettingsRepository,
    private val downloadCoordinator: DownloadCoordinator,
    private val quickCalibrationRunner: QuickCalibrationRunner? = null,
    private val calibrationSource: CalibrationSource = NoCalibrationSource,
) : ViewModel() {

    private val downloadAdmissionPolicy = DownloadAdmissionPolicy()
    private val downloadStorageEstimator = DownloadStorageEstimator()
    private val downloadEvidenceFactory = DownloadEvidenceFactory(PersistedModelEvidenceCodec())
    private val settings = settingsRepository.getSettings()
        .stateIn(viewModelScope, SharingStarted.Eagerly, com.debanshu777.caraml.core.settings.AppSettings())

    private val _recommendedModels = MutableStateFlow<List<RecommendedModelUiState>>(emptyList())
    val recommendedModels: StateFlow<List<RecommendedModelUiState>> = _recommendedModels.asStateFlow()

    private val _recommendationOrdering = MutableStateFlow(RecommendationOrdering.SERVER)
    val recommendationOrdering: StateFlow<RecommendationOrdering> = _recommendationOrdering.asStateFlow()

    private val _modelOrdering = MutableStateFlow<ModelOrdering>(ModelOrdering.Server(ModelSort.TRENDING))
    val modelOrdering: StateFlow<ModelOrdering> = _modelOrdering.asStateFlow()

    private var recommendationSession: RecommendationQuerySession? = null
    private var recommendationJob: Job? = null
    private var recommendationAppendJob: Job? = null
    private var listRequestJob: Job? = null
    private var searchRequestJob: Job? = null
    private var calibrationJob: Job? = null
    @Volatile private var activeModelRequestOwner: Any = Any()

    private val _quickCalibration = MutableStateFlow(QuickCalibrationUiState())
    val quickCalibration: StateFlow<QuickCalibrationUiState> = _quickCalibration.asStateFlow()

    init {
        viewModelScope.launch {
            settings.map { it.recommendationProfile }
                .distinctUntilChanged()
                .drop(1)
                .collectLatest { profile ->
                    recommendationSession?.let { session ->
                        try {
                            recommendationService.rerank(session, profile)
                        } catch (_: QuerySupersededCancellationException) {
                            // A new query owns recommendation state now; keep observing profile changes.
                        }
                    }
                }
        }
        viewModelScope.launch {
            calibrationSource.revisionUpdates()
                .distinctUntilChanged()
                .drop(1)
                .collectLatest {
                    recommendationSession?.let { session ->
                        try {
                            recommendationService.reassessCached(
                                session,
                                settings.value.recommendationProfile,
                            )
                        } catch (_: QuerySupersededCancellationException) {
                            // A newer query owns recommendation state.
                        }
                    }
                }
        }
    }

    private val deviceInfoRefresh = MutableStateFlow(0L)

    /** Re-sample platform readings without changing the recommendation profile or model query. */
    fun refreshDeviceInfo() {
        deviceInfoRefresh.value += 1L
    }

    val storageInfo: StateFlow<StorageInfoUiState> =
        combine(localModelRepository.getTotalDownloadedSizeBytes(), deviceInfoRefresh) { usedBytes, _ ->
                val totalStorage = readDeviceValue { storagePathProvider.getTotalStorageBytes() }?.takeIf { it > 0L }
                val freeStorage = readDeviceValue { storagePathProvider.getAvailableStorageBytes() }?.takeIf { it >= 0L }
                StorageInfoUiState(
                    totalDeviceBytes = if (freeStorage != null) totalStorage ?: 0L else 0L,
                    availableDeviceBytes = freeStorage ?: 0L,
                    usedByModelsBytes = usedBytes.coerceAtLeast(0L),
                    deviceHints = readDeviceValue { deviceCapabilities.getDeviceHints() },
                    hardwareProfile = readDeviceValue { deviceCapabilities.getHardwareProfile() },
                    resourceSnapshot = readDeviceValue { deviceCapabilities.getResourceSnapshot() },
                    hasSampled = true,
                )
            }
            .flowOn(Dispatchers.Default)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = StorageInfoUiState()
            )

    private inline fun <T> readDeviceValue(read: () -> T): T? = try {
        read()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private val _browseMode = MutableStateFlow(ModelHubBrowseMode.LanguageModels)
    val browseMode: StateFlow<ModelHubBrowseMode> = _browseMode.asStateFlow()

    private val sdCppCatalog = loadSdCppCuratedCatalog()

    private val _listParams = MutableStateFlow(
        ListModelsParams(
            minParams = ParameterRange.ZERO,
            maxParams = ParameterRange.SIX_B,
            sort = ModelSort.TRENDING
        )
    )
    val listParams: StateFlow<ListModelsParams> = _listParams.asStateFlow()

    private val _listResponse = MutableStateFlow<ListModelsResponse?>(null)
    val listResponse: StateFlow<ListModelsResponse?> = _listResponse.asStateFlow()

    private val _isListLoading = MutableStateFlow(false)
    val isListLoading: StateFlow<Boolean> = _isListLoading.asStateFlow()

    private val _listError = MutableStateFlow<String?>(null)
    val listError: StateFlow<String?> = _listError.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResponse = MutableStateFlow<SearchModelsResponse?>(null)
    val searchResponse: StateFlow<SearchModelsResponse?> = _searchResponse.asStateFlow()

    private val _isSearchLoading = MutableStateFlow(false)
    val isSearchLoading: StateFlow<Boolean> = _isSearchLoading.asStateFlow()

    private val _searchError = MutableStateFlow<String?>(null)
    val searchError: StateFlow<String?> = _searchError.asStateFlow()

    private val _results = MutableStateFlow(ModelHubResultsState())
    val results: StateFlow<ModelHubResultsState> = combine(
        _results, _recommendedModels, _modelOrdering,
    ) { page, recommendations, ordering ->
        page.copy(recommendations = recommendations, modelOrdering = ordering)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ModelHubResultsState())
    private var modelPager: Pager<ModelPageCursor, ListModelsResponse.Model>? = null
    private var searchDebounceJob: Job? = null
    private var lastAutoRequestedCursor: ModelPageCursor? = null

    private val _modelDetail = MutableStateFlow<ModelDetailResponse?>(null)
    val modelDetail: StateFlow<ModelDetailResponse?> = _modelDetail.asStateFlow()

    private val _isDetailLoading = MutableStateFlow(false)
    val isDetailLoading: StateFlow<Boolean> = _isDetailLoading.asStateFlow()

    private val _detailError = MutableStateFlow<String?>(null)
    val detailError: StateFlow<String?> = _detailError.asStateFlow()

    private val _ggufFiles = MutableStateFlow<List<GgufFileUiState>>(emptyList())
    val ggufFiles: StateFlow<List<GgufFileUiState>> = _ggufFiles.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    /** Exact artifact currently transferring; null during admission and between transfers. */
    private val _activeDownloadArtifact = MutableStateFlow<DownloadArtifactIdentity?>(null)
    val activeDownloadArtifact: StateFlow<DownloadArtifactIdentity?> =
        _activeDownloadArtifact.asStateFlow()

    private val _downloadError = MutableStateFlow<String?>(null)
    val downloadError: StateFlow<String?> = _downloadError.asStateFlow()
    private val _downloadBatches = MutableStateFlow<List<DownloadBatchSnapshot>>(emptyList())
    val downloadQueue: StateFlow<List<DownloadBatchSnapshot>> = downloadCoordinator.observeQueue()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val _actionableDownloadControls =
        MutableStateFlow<Map<Pair<String, String>, DurableDownloadControlUiState>>(emptyMap())
    private var downloadObservationJob: Job? = null
    private val refreshedCompletedBatchIds = mutableSetOf<String>()

    private var pendingDownloadForLater: PendingDownloadForLater? = null
    private val _showDownloadForLaterConfirmation = MutableStateFlow(false)
    val showDownloadForLaterConfirmation: StateFlow<Boolean> =
        _showDownloadForLaterConfirmation.asStateFlow()

    private val _setupComponents = MutableStateFlow<List<SetupComponentUiState>>(emptyList())
    val setupComponents: StateFlow<List<SetupComponentUiState>> = _setupComponents.asStateFlow()
    private var exactSetupComponentMetadata: Map<SdCppComponent, DownloadMetadataDTO> = emptyMap()
    private val _validatedBundleReady = MutableStateFlow(false)

    private val _isDownloadingSetupComponents = MutableStateFlow(false)
    val isDownloadingSetupComponents: StateFlow<Boolean> = _isDownloadingSetupComponents.asStateFlow()

    private val _setupDownloadError = MutableStateFlow<String?>(null)
    val setupDownloadError: StateFlow<String?> = _setupDownloadError.asStateFlow()

    /** The variant path currently selected by the user in the install bundle card. */
    private val _selectedVariantPath = MutableStateFlow<String?>(null)

    /** Live progress snapshot, updated on every download tick. */
    private val _installProgress = MutableStateFlow(InstallProgress())

    /**
     * Unified install state combining main model variants, required components, and overall
     * install readiness. Derived reactively from underlying state flows.
     */
    val installBundleState: StateFlow<InstallBundleUiState> = combine(
        _ggufFiles,
        _setupComponents,
        _isDownloading,
        _downloadError,
        _selectedVariantPath,
    ) { ggufFiles, setupComponents, isInstalling, installError, selectedPath ->
        val modelId = _modelDetail.value?.let { it.modelId ?: it.id } ?: ""
        val setup = if (modelId.isNotBlank()) getModelSetup(modelId) else null
        val isSelfContained = setup?.selfContained ?: (setupComponents.isEmpty())

        val mainModelDownloaded = ggufFiles.any { it.isDownloaded }
        val allRequiredComponentsReady = setupComponents.filter { it.required }.all { it.isDownloaded }
        val isReady = mainModelDownloaded && (isSelfContained || allRequiredComponentsReady)

        val variantBytes = if (mainModelDownloaded) 0L else {
            val sel = selectedPath?.let { p -> ggufFiles.find { it.path == p && !it.isDownloaded } }
            sel?.sizeBytes ?: ggufFiles.filter { !it.isDownloaded }
                .minByOrNull { it.sizeBytes ?: Long.MAX_VALUE }?.sizeBytes ?: 0L
        }
        val componentBytes = setupComponents
            .filter { !it.isDownloaded && it.required }
            .sumOf { it.sizeHint?.let { h -> parseSizeHint(h) } ?: 0L }

        InstallBundleUiState(
            variants = ggufFiles,
            selectedVariantPath = selectedPath,
            components = setupComponents,
            totalNewDownloadBytes = variantBytes + componentBytes,
            isInstalling = isInstalling,
            installError = installError,
            isReady = isReady,
            isSelfContained = isSelfContained,
            durableControl = selectedPath
                ?.let { path -> ggufFiles.singleOrNull { it.path == path } }
                ?.durableControl,
        )
    }.combine(_validatedBundleReady) { bundle, validatedBundleReady ->
        bundle.copy(isReady = bundle.isReady && validatedBundleReady)
    }.combine(_installProgress) { bundle, progress ->
        bundle.copy(
            overallProgress = progress.fraction,
            overallBytesReceived = progress.bytesReceived,
            overallBytesTotal = progress.bytesTotal,
            currentDownloadLabel = progress.label,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = InstallBundleUiState()
    )

    private fun modelTypeForCurrentBrowseMode(): String =
        when (_browseMode.value) {
            ModelHubBrowseMode.LanguageModels -> ModelType.TEXT
            ModelHubBrowseMode.DiffusionImage -> ModelType.IMAGE
            ModelHubBrowseMode.DiffusionVideo -> ModelType.VIDEO
        }

    fun setBrowseMode(mode: ModelHubBrowseMode) {
        val previous = _browseMode.value
        if (previous != mode) invalidateModelRequests()
        _browseMode.value = mode
        when (mode) {
            ModelHubBrowseMode.LanguageModels -> {
                if (previous != ModelHubBrowseMode.LanguageModels) {
                    _listResponse.update { null }
                    loadModels()
                }
            }

            ModelHubBrowseMode.DiffusionImage -> {
                resetSearchStateForCuratedHub()
                _listError.update { null }
                _isListLoading.update { false }
                val response = sdCppCatalog.image.toListModelsResponse()
                _listResponse.update { response }
                _results.value = ModelHubResultsState(
                    key = ModelQueryKey(mode, null, _listParams.value.sort, _listParams.value.minParams, _listParams.value.maxParams),
                    models = response.models.orEmpty().filterNotNull().take(MAX_LOADED_MODELS),
                )
                startRecommendations(response.models.orEmpty().filterNotNull(), defaultRecommendationWorkload(mode))
            }

            ModelHubBrowseMode.DiffusionVideo -> {
                resetSearchStateForCuratedHub()
                _listError.update { null }
                _isListLoading.update { false }
                val response = sdCppCatalog.video.toVideoListModelsResponse()
                _listResponse.update { response }
                _results.value = ModelHubResultsState(
                    key = ModelQueryKey(mode, null, _listParams.value.sort, _listParams.value.minParams, _listParams.value.maxParams),
                    models = response.models.orEmpty().filterNotNull().take(MAX_LOADED_MODELS),
                )
                startRecommendations(response.models.orEmpty().filterNotNull(), defaultRecommendationWorkload(mode))
            }
        }
    }

    /** Starts the first Discover query when that surface first becomes visible. */
    fun ensureDiscoverLoaded() {
        if (_browseMode.value == ModelHubBrowseMode.LanguageModels && _results.value.key == null) {
            startPageQuery(null)
        }
    }

    fun loadModels() {
        if (_browseMode.value != ModelHubBrowseMode.LanguageModels) return
        startPageQuery(_results.value.key?.committedSearch)
    }

    fun restartCurrentQuery() = loadModels()

    private fun startPageQuery(committedSearch: String?) {
        val params = _listParams.value
        val key = ModelQueryKey(
            mode = ModelHubBrowseMode.LanguageModels,
            committedSearch = committedSearch,
            remoteSort = params.sort,
            minParams = params.minParams,
            maxParams = params.maxParams,
        )
        val request = try {
            ModelPageRequest(
                search = committedSearch,
                sort = key.remoteSort,
                minParams = key.minParams,
                maxParams = key.maxParams,
            )
        } catch (_: IllegalArgumentException) {
            _searchError.value = "Search or sort is unsupported. Refine your search and try again."
            return
        }
        val owner = beginModelRequest()
        lastAutoRequestedCursor = null
        _results.value = ModelHubResultsState(key = key, initialLoading = true)
        _listResponse.value = null
        _searchResponse.value = null
        _listError.value = null
        _searchError.value = null
        _isListLoading.value = committedSearch == null
        _isSearchLoading.value = committedSearch != null
        val pager = Pager(
            config = PagingConfig(pageSize = request.limit, initialLoadSize = request.limit,
                prefetchDistance = 1, enablePlaceholders = false),
            pagingSourceFactory = {
                ModelHubPagingSource(
                    request = request,
                    fetch = { pageRequest -> api.getModelPage(pageRequest) },
                    validId = recommendationService::validRepositoryId,
                    onLoadStart = { append ->
                        if (ownsModelRequest(owner) && _results.value.key == key) {
                            _results.value = if (append) _results.value.copy(moreLoading = true, moreError = null)
                                else _results.value.copy(initialLoading = true, initialError = null)
                        }
                    },
                    onPage = { added, next, total, stalled ->
                        if (ownsModelRequest(owner) && _results.value.key == key) {
                            val prior = _results.value
                            val all = prior.models + added
                            val append = prior.models.isNotEmpty()
                            _results.value = prior.copy(
                                models = all,
                                nextCursor = next,
                                totalCount = total ?: prior.totalCount,
                                initialLoading = false,
                                moreLoading = false,
                                initialError = null,
                                moreError = if (stalled) "The model catalog did not advance. Refine your search to continue." else null,
                                sessionLimitReached = all.size >= MAX_LOADED_MODELS,
                            )
                            _isListLoading.value = false
                            _isSearchLoading.value = false
                            _listResponse.value = ListModelsResponse(models = all, numTotalItems = total ?: prior.totalCount)
                            if (committedSearch != null) {
                                _searchResponse.value = SearchModelsResponse(
                                    models = all.map { model -> SearchModelsResponse.Model(id = model.id, `private` = model.`private`) },
                                    q = committedSearch,
                                )
                            }
                            if (!append) {
                                startRecommendations(all, defaultRecommendationWorkload(ModelHubBrowseMode.LanguageModels),
                                    source = if (committedSearch == null) "browse" else "search")
                            } else {
                                val session = recommendationSession
                                if (session != null && added.isNotEmpty()) {
                                    recommendationAppendJob?.cancel()
                                    recommendationAppendJob = viewModelScope.launch {
                                        try {
                                            recommendationService.appendCandidates(session, added)
                                            while (ownsModelRequest(owner) && recommendationSession === session &&
                                                session.evaluatedCount < minOf(session.candidates.size, 96)) {
                                                recommendationService.evaluateMore(session, settings.value.recommendationProfile)
                                            }
                                        } catch (_: QuerySupersededCancellationException) {
                                            // A newer query owns the recommendation session.
                                        }
                                    }
                                }
                            }
                        }
                    },
                )
            },
        )
        modelPager = pager
        listRequestJob = viewModelScope.launch {
            pager.flow.asItemSnapshotListFlow { loadStates ->
                if (!ownsModelRequest(owner) || _results.value.key != key) return@asItemSnapshotListFlow
                val refreshError = loadStates.refresh as? LoadState.Error
                val appendError = loadStates.append as? LoadState.Error
                val failure = refreshError ?: appendError
                if (failure != null) {
                    val message = (failure.error as? ModelHubPageException)?.let { networkErrorMessage(it.networkError) }
                        ?: "Unable to load models. Please try again."
                    if (refreshError != null && _results.value.models.isEmpty()) {
                        _results.value = _results.value.copy(initialLoading = false, initialError = message)
                        if (committedSearch == null) _listError.value = message else _searchError.value = message
                    } else {
                        _results.value = _results.value.copy(moreLoading = false, moreError = message)
                    }
                    _isListLoading.value = false
                    _isSearchLoading.value = false
                }
            }.collect { /* Keep Paging's snapshot presenter active for explicit append/retry. */ }
        }
    }

    fun loadNextPage() {
        val current = _results.value
        if (current.key?.mode != ModelHubBrowseMode.LanguageModels || current.initialLoading || current.moreLoading) return
        val pager = modelPager ?: return
        if (current.moreError != null && current.nextCursor != null) {
            pager.retry()
        } else if (current.hasMore) {
            pager.append()
        }
    }

    /** The viewport may report the same tail repeatedly while recommendations reorder. */
    fun autoLoadNextPage() {
        val current = _results.value
        val cursor = current.nextCursor ?: return
        if (!current.canLoadMore || current.moreError != null || cursor == lastAutoRequestedCursor) return
        lastAutoRequestedCursor = cursor
        loadNextPage()
    }

    private fun networkErrorMessage(error: DataError.Network): String = when (error) {
        DataError.Network.NoInternet -> "No internet connection. Please check your network and try again."
        DataError.Network.Serialization -> "The model catalog response could not be read."
        DataError.Network.Unauthorized -> "The model catalog could not be accessed."
        DataError.Network.NotFound -> "The model catalog is unavailable."
        DataError.Network.RequestTimeout -> "The request timed out. Please try again."
        DataError.Network.RateLimited -> "Too many requests. Please try again later."
        DataError.Network.Conflict -> "The request could not be completed. Please try again."
        DataError.Network.PayloadTooLarge -> "The response was too large. Refine your search."
        DataError.Network.ServerError -> "The model catalog is temporarily unavailable."
        DataError.Network.Unknown -> "Unable to load models. Please try again."
    }

    fun updateParams(
        sort: ModelSort? = null,
        minParams: ParameterRange? = null,
        maxParams: ParameterRange? = null
    ) {
        if (_browseMode.value != ModelHubBrowseMode.LanguageModels) return
        val current = _listParams.value
        val newMin = minParams ?: current.minParams
        val newMax = maxParams ?: current.maxParams
        val adjustedMax = if (newMax.ordinal < newMin.ordinal) newMin else newMax
        _listParams.update {
            it.copy(
                sort = sort ?: it.sort,
                minParams = newMin,
                maxParams = adjustedMax
            )
        }
    }

    fun setParameterFilters(minParams: ParameterRange, maxParams: ParameterRange) {
        if (_browseMode.value != ModelHubBrowseMode.LanguageModels) return
        val before = _listParams.value
        updateParams(minParams = minParams, maxParams = maxParams)
        if (_listParams.value != before) loadModels()
    }

    fun applyDiscoverFilters(
        mode: ModelHubBrowseMode,
        ordering: ModelOrdering,
        minParams: ParameterRange,
        maxParams: ParameterRange,
    ) {
        val priorMode = _browseMode.value
        val priorParams = _listParams.value
        val safeOrdering = when (ordering) {
            ModelOrdering.Personalized -> ordering
            is ModelOrdering.Server -> if (ordering.value in SUPPORTED_REMOTE_SORTS) ordering else ModelOrdering.Server(ModelSort.TRENDING)
        }
        _listParams.value = priorParams.copy(
            sort = (safeOrdering as? ModelOrdering.Server)?.value ?: priorParams.sort,
            minParams = minParams,
            maxParams = if (maxParams.ordinal < minParams.ordinal) minParams else maxParams,
        )
        _modelOrdering.value = safeOrdering
        setRecommendationOrdering(if (safeOrdering is ModelOrdering.Personalized) RecommendationOrdering.PERSONALIZED else RecommendationOrdering.SERVER)
        if (mode != priorMode) setBrowseMode(mode)
        else if (mode == ModelHubBrowseMode.LanguageModels && _listParams.value != priorParams) loadModels()
    }

    fun loadDetail(
        modelId: String,
        hubBrowseMode: ModelHubBrowseMode = ModelHubBrowseMode.LanguageModels,
    ) {
        if (modelId.isBlank()) return
        observeDurableDownloads(modelId)
        _browseMode.value = hubBrowseMode
        startRecommendations(
            models = listOf(ListModelsResponse.Model(id = modelId)),
            workload = defaultRecommendationWorkload(hubBrowseMode),
            source = "details",
        )
        viewModelScope.launch {
            _isDetailLoading.update { true }
            _detailError.update { null }
            _modelDetail.update { null }
            _ggufFiles.update { emptyList() }
            _setupComponents.update { emptyList() }
            exactSetupComponentMetadata = emptyMap()
            _validatedBundleReady.update { false }
            _selectedVariantPath.update { null }

            when (val detailResult = api.getModelDetail(modelId)) {
                is Result.Success -> {
                    _modelDetail.update { detailResult.data }
                    _detailError.update { null }

                    val weightFilter = when (hubBrowseMode) {
                        ModelHubBrowseMode.LanguageModels -> ModelFileWeightFilter.GgufOnly
                        ModelHubBrowseMode.DiffusionImage,
                        ModelHubBrowseMode.DiffusionVideo ->
                            ModelFileWeightFilter.StableDiffusionCppWeights
                    }
                    when (val treeResult = api.getModelFileTree(modelId, weightFilter)) {
                        is Result.Success -> {
                            val downloaded = localModelRepository.getDownloadedFilenames(modelId)
                            _ggufFiles.update {
                                treeResult.data.map { item ->
                                    val rel = item.path ?: ""
                                    val fn = rel.substringAfterLast('/').ifEmpty { rel }
                                    val isDownloaded = when (hubBrowseMode) {
                                        ModelHubBrowseMode.DiffusionImage,
                                        ModelHubBrowseMode.DiffusionVideo,
                                        -> false
                                        ModelHubBrowseMode.LanguageModels ->
                                            fn in downloaded || rel in downloaded
                                    }
                                    val exactSize = item.lfs?.size ?: item.size
                                    val artifact = DownloadArtifactIdentity.create(
                                        repositoryId = modelId,
                                        immutableRevision = detailResult.data.sha.orEmpty(),
                                        relativePath = rel,
                                        remoteObjectId = item.lfs?.oid?.let { "sha256:$it" } ?: item.xetHash ?: item.oid,
                                        expectedBytes = exactSize ?: 0L,
                                    )
                                    GgufFileUiState(
                                        path = rel,
                                        filename = fn,
                                        sizeBytes = exactSize,
                                        isDownloaded = isDownloaded,
                                        progress = null,
                                        artifact = artifact,
                                    )
                                }
                            }
                            // Auto-select the first undownloaded variant for diffusion models
                            if (hubBrowseMode != ModelHubBrowseMode.LanguageModels) {
                                _selectedVariantPath.update { _ggufFiles.value.firstOrNull()?.path }
                                loadSetupComponentsForModel(modelId)
                            }
                            projectDurableDownloadState(_downloadBatches.value)
                            refreshNewlyCompletedBatches(modelId, _downloadBatches.value)
                        }
                        is Result.Error -> {
                        }
                    }
                }
                is Result.Error -> {
                    _detailError.update {
                        when (detailResult.error) {
                            DataError.Network.NoInternet ->
                                "No internet connection. Please check your network and try again."
                            DataError.Network.Serialization ->
                                "Failed to process server response. The data format may be invalid."
                            DataError.Network.Unauthorized ->
                                "Authentication failed. Please check your credentials."
                            DataError.Network.NotFound ->
                                "Could not load model details. Please try again."
                            DataError.Network.RequestTimeout ->
                                "Request timed out. The server took too long to respond."
                            DataError.Network.RateLimited ->
                                "Too many requests. Please try again later."
                            DataError.Network.Conflict ->
                                "Request conflict. Please refresh and try again."
                            DataError.Network.PayloadTooLarge ->
                                "Request too large. The model ID may be invalid."
                            DataError.Network.ServerError ->
                                "Server error occurred. Please try again later."
                            DataError.Network.Unknown ->
                                "Could not load model details. Please try again."
                        }
                    }
                }
            }
            _isDetailLoading.update { false }
        }
    }

    /** Called when the user taps a different quantization variant in the detail page. */
    fun selectVariant(path: String) {
        _selectedVariantPath.update { path }
        projectDurableDownloadState(_downloadBatches.value)
    }

    /**
     * Smart install: downloads the selected variant + all missing required components in sequence.
     * Shared components (already on disk) are skipped. Records everything in the DB.
     */
    fun smartInstall(modelId: String) {
        startSmartInstall(modelId, requestedVariantPath = null, downloadForLaterConfirmed = false)
    }

    private fun startSmartInstall(
        modelId: String,
        requestedVariantPath: String?,
        downloadForLaterConfirmed: Boolean,
    ) {
        if (_isDownloading.value) return
        _isDownloading.value = true
        viewModelScope.launch {
            _downloadError.update { null }
            val modelType = modelTypeForCurrentBrowseMode()
            var enqueued = false
            try {
                val variantPath = requestedVariantPath
                    ?: _selectedVariantPath.value
                    ?: _ggufFiles.value.firstOrNull()?.path
                    ?: return@launch

                val setup = getModelSetup(modelId)
                val allRequiredComponents = setup?.components?.filter { it.required }.orEmpty()
                val componentMetadata = mutableMapOf<SdCppComponent, DownloadMetadataDTO>()
                for (component in allRequiredComponents) {
                    componentMetadata[component] = exactSetupComponentMetadata[component]
                        ?: createMetadataForComponent(component)
                }
                val ownedArtifacts = createExactBundleMetadata(variantPath, componentMetadata)
                    ?: throw ArtifactVerificationException()
                val selectedPaths = selectDiffusionFilesToDownload(
                    _ggufFiles.value.filter { it.path.isNotBlank() },
                    variantPath,
                ).mapTo(mutableSetOf()) { it.path }
                val primaryMetadata = ownedArtifacts.filter { metadata ->
                    metadata.artifact.repositoryId == modelId && metadata.artifact.relativePath in selectedPaths
                }
                val ownedComponentMetadata = componentMetadata.mapValues { (_, metadata) ->
                    ownedArtifacts.single { it.artifact == metadata.artifact }
                }
                val missingPrimaryIdentities = _ggufFiles.value
                    .filter { it.path in selectedPaths && !it.isDownloaded }
                    .mapTo(mutableSetOf()) { it.artifact }
                val missingComponentIdentities = _setupComponents.value
                    .filter { it.required && !it.isDownloaded }
                    .mapNotNullTo(mutableSetOf()) { state ->
                        ownedComponentMetadata.entries.singleOrNull { (component) ->
                            component.repoId == state.repoId && component.filePath == state.filePath
                        }?.value?.artifact
                    }
                val installBytesTotal = ownedArtifacts
                    .filter { it.artifact in missingPrimaryIdentities || it.artifact in missingComponentIdentities }
                    .sumOf { it.artifact.expectedBytes }
                _installProgress.update { InstallProgress(bytesTotal = installBytesTotal) }

                val selectedMetadata = primaryMetadata.singleOrNull { it.artifact.relativePath == variantPath }
                    ?: throw IllegalStateException("Exact artifact identity is unavailable")

                val artifacts = ownedArtifacts.map { metadata ->
                    DownloadArtifactRequest(
                        metadata = metadata,
                        primary = metadata.artifact.repositoryId == modelId,
                    )
                }

                when (val admission = refreshDownloadAdmission(modelId, selectedMetadata, artifacts)) {
                    DownloadAdmission.Allowed -> Unit
                    is DownloadAdmission.ConfirmationRequired -> {
                        if (!downloadForLaterConfirmed) {
                            requestDownloadForLaterConfirmation(
                                PendingDownloadForLater.Smart(modelId, variantPath),
                            )
                            return@launch
                        }
                    }
                    is DownloadAdmission.Blocked -> {
                        _downloadError.value = downloadAdmissionErrorMessage(admission)
                        return@launch
                    }
                }

                downloadCoordinator.enqueue(
                    DownloadBatchRequest(
                        ownerModelId = modelId,
                        modelType = modelType,
                        artifacts = artifacts,
                        evidence = downloadEvidenceFactory.create(
                            artifacts = artifacts,
                            descriptor = selectedDescriptorFor(modelId),
                        ),
                        downloadForLaterConfirmed = downloadForLaterConfirmed,
                        displayName = modelId,
                    ),
                )
                enqueued = true
            } catch (e: DownloadAdmissionRejected) {
                when (e.admission) {
                    is DownloadAdmission.ConfirmationRequired -> _selectedVariantPath.value?.let { variantPath ->
                        requestDownloadForLaterConfirmation(PendingDownloadForLater.Smart(modelId, variantPath))
                    } ?: run { _downloadError.value = downloadAdmissionErrorMessage(e.admission) }
                    else -> _downloadError.value = downloadAdmissionErrorMessage(e.admission)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: InsufficientStorageException) {
                val required = formatBytes(e.requiredBytes)
                val available = formatBytes(e.availableBytes)
                _downloadError.update {
                    "Not enough storage space. Need $required but only $available is available."
                }
                _ggufFiles.update { list -> list.map { it.copy(progress = null) } }
                _setupComponents.update { list -> list.map { it.copy(progress = null) } }
            } catch (_: IncompleteDownloadException) {
                _downloadError.update { "Download was interrupted and the file is incomplete. Please try again." }
                _ggufFiles.update { list -> list.map { it.copy(progress = null) } }
                _setupComponents.update { list -> list.map { it.copy(progress = null) } }
            } catch (_: ArtifactFileAccessException) {
                _downloadError.update { "Model storage is unavailable. Please check storage access and try again." }
                _ggufFiles.update { list -> list.map { it.copy(progress = null) } }
                _setupComponents.update { list -> list.map { it.copy(progress = null) } }
            } catch (_: Exception) {
                _downloadError.update { "Install failed. Please check your connection and try again." }
                _ggufFiles.update { list -> list.map { it.copy(progress = null) } }
                _setupComponents.update { list -> list.map { it.copy(progress = null) } }
            } finally {
                _activeDownloadArtifact.value = null
                if (!enqueued) {
                    _isDownloading.update { false }
                    _installProgress.update { InstallProgress() }
                }
            }
        }
    }

    /** Routes the selected artifact into the durable language or diffusion download flow. */
    fun startDownload(modelId: String, path: String, metadata: DownloadMetadataDTO) {
        val diffusionHub = when (_browseMode.value) {
            ModelHubBrowseMode.DiffusionImage, ModelHubBrowseMode.DiffusionVideo -> true
            ModelHubBrowseMode.LanguageModels -> false
        }
        if (diffusionHub) {
            // For diffusion: select the tapped variant and kick off smart install
            _selectedVariantPath.update { path }
            startSmartInstall(modelId, requestedVariantPath = path, downloadForLaterConfirmed = false)
            return
        }
        startLanguageDownload(modelId, path, metadata, downloadForLaterConfirmed = false)
    }

    /** Enqueues all exact files belonging to one selected language-model shard configuration. */
    fun startLanguageBundleDownload(
        modelId: String,
        metadata: List<DownloadMetadataDTO>,
        downloadForLaterConfirmed: Boolean = false,
    ) {
        if (_browseMode.value != ModelHubBrowseMode.LanguageModels ||
            metadata.isEmpty() || metadata.size > DescriptorLimits.MAX_COMPONENTS ||
            _isDownloading.value
        ) return
        val identities = metadata.map { it.artifact }
        if (identities.map { it.relativePath }.toSet().size != identities.size ||
            metadata.any { item ->
                item.logicalRole != "model" || item.artifact.repositoryId != modelId ||
                    !isExactCurrentDetailArtifact(modelId, item.artifact)
            }
        ) return
        val matchingBrowseVariant = _recommendedModels.value
            .singleOrNull { it.repositoryId == modelId }
            ?.browseVariants
            ?.singleOrNull { matchesExactBrowseGroup(it, identities) }
            ?: return

        _selectedVariantPath.value = identities.first().relativePath
        _isDownloading.value = true
        viewModelScope.launch {
            _downloadError.update { null }
            var enqueued = false
            try {
                val requests = metadata.map { DownloadArtifactRequest(it, primary = true) }
                when (val admission = refreshDownloadAdmission(
                    modelId,
                    metadata.first(),
                    requests,
                    browseVariantIdentity = matchingBrowseVariant.stableIdentity,
                )) {
                    DownloadAdmission.Allowed -> Unit
                    is DownloadAdmission.ConfirmationRequired -> {
                        if (!downloadForLaterConfirmed) {
                            requestDownloadForLaterConfirmation(PendingDownloadForLater.Group(modelId, metadata))
                            return@launch
                        }
                    }
                    is DownloadAdmission.Blocked -> {
                        _downloadError.value = downloadAdmissionErrorMessage(admission)
                        return@launch
                    }
                }
                downloadCoordinator.enqueue(
                    DownloadBatchRequest(
                        ownerModelId = modelId,
                        modelType = ModelType.TEXT,
                        artifacts = requests,
                        evidence = downloadEvidenceFactory.create(requests, selectedDescriptorFor(modelId)),
                        downloadForLaterConfirmed = downloadForLaterConfirmed,
                        displayName = "$modelId (${requests.size} parts)",
                    ),
                )
                enqueued = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: InsufficientStorageException) {
                _downloadError.value = "Not enough storage space. Need ${formatBytes(e.requiredBytes)} but only ${formatBytes(e.availableBytes)} is available."
            } catch (_: Exception) {
                _downloadError.value = "Download failed. Please check your connection and try again."
            } finally {
                if (!enqueued) _isDownloading.update { false }
            }
        }
    }

    private fun startLanguageDownload(
        modelId: String,
        path: String,
        metadata: DownloadMetadataDTO,
        downloadForLaterConfirmed: Boolean,
    ) {
        if (_isDownloading.value) return
        _isDownloading.value = true
        viewModelScope.launch {
            _downloadError.update { null }
            var enqueued = false
            try {
                val artifacts = listOf(DownloadArtifactRequest(metadata, primary = true))
                when (val admission = refreshDownloadAdmission(modelId, metadata, artifacts)) {
                    DownloadAdmission.Allowed -> Unit
                    is DownloadAdmission.ConfirmationRequired -> {
                        if (!downloadForLaterConfirmed) {
                            requestDownloadForLaterConfirmation(
                                PendingDownloadForLater.Single(modelId, path, metadata),
                            )
                            return@launch
                        }
                    }
                    is DownloadAdmission.Blocked -> {
                        _downloadError.value = downloadAdmissionErrorMessage(admission)
                        return@launch
                    }
                }
                downloadCoordinator.enqueue(
                    DownloadBatchRequest(
                        ownerModelId = modelId,
                        modelType = ModelType.TEXT,
                        artifacts = artifacts,
                        evidence = downloadEvidenceFactory.create(
                            artifacts = artifacts,
                            descriptor = selectedDescriptorFor(modelId),
                        ),
                        downloadForLaterConfirmed = downloadForLaterConfirmed,
                        displayName = modelId,
                    ),
                )
                enqueued = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: InsufficientStorageException) {
                val required = formatBytes(e.requiredBytes)
                val available = formatBytes(e.availableBytes)
                _downloadError.update {
                    "Not enough storage space. Need $required but only $available is available."
                }
                _ggufFiles.update { list -> list.map { it.copy(progress = null) } }
            } catch (_: IncompleteDownloadException) {
                _downloadError.update { "Download was interrupted and the file is incomplete. Please try again." }
                _ggufFiles.update { list -> list.map { it.copy(progress = null) } }
            } catch (_: ArtifactFileAccessException) {
                _downloadError.update { "Model storage is unavailable. Please check storage access and try again." }
                _ggufFiles.update { list -> list.map { it.copy(progress = null) } }
            } catch (_: Exception) {
                _downloadError.update { "Download failed. Please check your connection and try again." }
                _ggufFiles.update { list -> list.map { it.copy(progress = null) } }
            } finally {
                _activeDownloadArtifact.value = null
                if (!enqueued) _isDownloading.update { false }
            }
        }
    }

    fun pauseDownload(batchId: String, artifactId: String) {
        dispatchDownloadControl(batchId, artifactId, DownloadControlCommand.PAUSE)
    }

    fun resumeDownload(batchId: String, artifactId: String) {
        dispatchDownloadControl(batchId, artifactId, DownloadControlCommand.RESUME)
    }

    fun cancelDownload(batchId: String, artifactId: String) {
        dispatchDownloadControl(batchId, artifactId, DownloadControlCommand.CANCEL)
    }

    fun retryDownload(batchId: String, artifactId: String) {
        dispatchDownloadControl(batchId, artifactId, DownloadControlCommand.RETRY)
    }

    private fun dispatchDownloadControl(
        batchId: String,
        artifactId: String,
        command: DownloadControlCommand,
    ) {
        viewModelScope.launch {
            val batch = downloadQueue.value.singleOrNull { it.batchId == batchId } ?: return@launch
            if (batch.artifacts.none { it.artifactId == artifactId } ||
                !command.accepts(batch.state, batch.userIntent)) return@launch
            when (command) {
                DownloadControlCommand.PAUSE -> downloadCoordinator.pause(batch.batchId)
                DownloadControlCommand.RESUME -> downloadCoordinator.resume(batch.batchId)
                DownloadControlCommand.CANCEL -> downloadCoordinator.cancel(batch.batchId)
                DownloadControlCommand.RETRY -> downloadCoordinator.retry(batch.batchId)
            }
        }
    }

    private fun observeDurableDownloads(modelId: String) {
        downloadObservationJob?.cancel()
        refreshedCompletedBatchIds.clear()
        _downloadBatches.value = emptyList()
        projectDurableDownloadState(emptyList())
        downloadObservationJob = viewModelScope.launch {
            downloadCoordinator.observeForModel(modelId).collectLatest { batches ->
                _downloadBatches.value = batches
                projectDurableDownloadState(batches)
                refreshNewlyCompletedBatches(modelId, batches)
            }
        }
    }

    private suspend fun refreshNewlyCompletedBatches(
        modelId: String,
        batches: List<DownloadBatchSnapshot>,
    ) {
        val hydratedModelId = _modelDetail.value?.let { it.modelId ?: it.id }
        if (hydratedModelId != modelId || _ggufFiles.value.isEmpty()) return
        val currentRequestBundles = currentExpectedRequestBundles().values.distinct()
        if (currentRequestBundles.isEmpty()) return

        val newlyCompletedBatchIds = batches.asSequence()
            .filter { batch ->
                batch.state == DownloadBatchState.COMPLETED &&
                    currentRequestBundles.any(batch::matchesExactRequests)
            }
            .map(DownloadBatchSnapshot::batchId)
            .filterNot(refreshedCompletedBatchIds::contains)
            .toSet()
        if (newlyCompletedBatchIds.isEmpty()) return

        refreshGgufFilesDownloadState(
            modelId = modelId,
            isDiffusion = _browseMode.value != ModelHubBrowseMode.LanguageModels,
        )
        refreshedCompletedBatchIds += newlyCompletedBatchIds
        projectDurableDownloadState(batches)
    }

    private fun projectDurableDownloadState(batches: List<DownloadBatchSnapshot>) {
        val activeBatchStates = setOf(
            DownloadBatchState.QUEUED,
            DownloadBatchState.RUNNING,
            DownloadBatchState.WAITING_FOR_NETWORK,
            DownloadBatchState.VERIFYING,
        )
        val currentRequestBundles = currentExpectedRequestBundles()
        val selectionsByArtifact = currentRequestBundles.mapNotNull { (artifact, requests) ->
            relevantDownloadTask(batches, requests, artifact)?.let { artifact to it }
        }.toMap()
        val relevantBatches = selectionsByArtifact.values
            .map(RelevantDownloadTask::batch)
            .distinctBy { it.batchId }
        _isDownloading.value = relevantBatches.any { it.state in activeBatchStates }

        val selectedArtifact = _selectedVariantPath.value?.let { selectedPath ->
            _ggufFiles.value.singleOrNull { it.path == selectedPath }?.artifact
        }
        val selectedRequests = selectedArtifact?.let(currentRequestBundles::get)
        val selectedTask = selectedArtifact?.let(selectionsByArtifact::get)
        val runningTask = selectedTask?.batch?.artifacts?.asSequence()
            ?.filter { it.state == DownloadArtifactState.RUNNING }
            ?.map { task -> RelevantDownloadTask(selectedTask.batch, task) }
            ?.minByOrNull { it.task.artifactId }
            ?: relevantBatches.asSequence()
            .flatMap { batch ->
                batch.artifacts.asSequence().map { task -> RelevantDownloadTask(batch, task) }
            }
            .filter { it.task.state == DownloadArtifactState.RUNNING }
            .minWithOrNull(
                compareBy<RelevantDownloadTask>(
                    { it.batch.batchId },
                    { it.task.artifactId },
                ),
            )
        _activeDownloadArtifact.value = runningTask?.task?.request?.metadata?.artifact

        _ggufFiles.update { files ->
            files.map { file ->
                val task = file.artifact?.let { artifact ->
                    selectionsByArtifact[artifact]?.task
                }
                val control = file.artifact?.let(selectionsByArtifact::get)?.toControlUiState()
                file.copy(
                    progress = task?.takeIf {
                        it.state == DownloadArtifactState.RUNNING && it.expectedBytes > 0L
                    }?.let {
                        it.bytesReceived.toFloat() * 100f / it.expectedBytes.toFloat()
                    },
                    durableControl = control,
                )
            }
        }
        val projectedControls = when (_browseMode.value) {
            ModelHubBrowseMode.LanguageModels -> selectionsByArtifact.values
            ModelHubBrowseMode.DiffusionImage,
            ModelHubBrowseMode.DiffusionVideo,
            -> listOfNotNull(selectedTask)
        }.map(RelevantDownloadTask::toControlUiState)
        _actionableDownloadControls.value = projectedControls.associateBy { control ->
            control.batchId to control.artifactId
        }
        _setupComponents.update { components ->
            components.map { component ->
                val expected = selectedRequests?.singleOrNull { request ->
                    request.metadata.artifact.let { identity ->
                        identity.repositoryId == component.repoId &&
                            identity.relativePath == component.filePath
                    }
                }
                val task = expected?.let { request ->
                    relevantDownloadTask(
                        batches = batches,
                        expectedRequests = selectedRequests,
                        artifact = request.metadata.artifact,
                    )?.task
                }
                component.copy(
                    progress = task?.takeIf {
                        it.state == DownloadArtifactState.RUNNING && it.expectedBytes > 0L
                    }?.let {
                        it.bytesReceived.toFloat() * 100f / it.expectedBytes.toFloat()
                    }
                )
            }
        }

        val projectionBatch = if (selectedArtifact != null) {
            selectedTask?.batch
        } else {
            relevantDownloadBatch(relevantBatches)
        }
        _installProgress.value = projectionBatch?.let { batch ->
            InstallProgress(
                fraction = batch.expectedBytes.takeIf { it > 0L }
                    ?.let { batch.bytesReceived.toFloat() / it.toFloat() },
                bytesReceived = batch.bytesReceived,
                bytesTotal = batch.expectedBytes,
                label = batch.artifacts.firstOrNull {
                    it.state == DownloadArtifactState.RUNNING
                }?.request?.metadata?.artifact?.relativePath?.substringAfterLast('/'),
            )
        } ?: InstallProgress()
        _downloadError.value = when (projectionBatch?.state) {
            DownloadBatchState.FAILED_RETRYABLE ->
                "Download paused after a problem. Retry when ready."
            DownloadBatchState.FAILED_TERMINAL ->
                "Download could not be verified. Please start it again."
            else -> null
        }
    }

    private fun currentExpectedRequestBundles(): Map<DownloadArtifactIdentity, List<DownloadArtifactRequest>> =
        _ggufFiles.value.mapNotNull { file ->
            val artifact = file.artifact ?: return@mapNotNull null
            val requests = expectedRequestsFor(file.path) ?: return@mapNotNull null
            artifact to requests
        }.toMap()

    private fun expectedRequestsFor(path: String): List<DownloadArtifactRequest>? {
        val detail = _modelDetail.value ?: return null
        val ownerModelId = detail.modelId ?: detail.id ?: return null
        return when (_browseMode.value) {
            ModelHubBrowseMode.LanguageModels -> {
                val artifact = _ggufFiles.value.singleOrNull { it.path == path }?.artifact ?: return null
                listOf(
                    DownloadArtifactRequest(
                        metadata = DownloadMetadataDTO(
                            artifact = artifact,
                            logicalRole = "model",
                            sizeBytes = artifact.expectedBytes,
                            author = detail.author,
                            libraryName = detail.libraryName,
                            pipelineTag = detail.pipelineTag,
                            contextLength = detail.gguf?.contextLength,
                        ),
                        primary = true,
                    ),
                )
            }
            ModelHubBrowseMode.DiffusionImage,
            ModelHubBrowseMode.DiffusionVideo,
            -> {
                val requiredComponents = getModelSetup(ownerModelId)
                    ?.components
                    ?.filter(SdCppComponent::required)
                    .orEmpty()
                if (!requiredComponents.all(exactSetupComponentMetadata::containsKey)) return null
                val metadata = createExactBundleMetadata(path, exactSetupComponentMetadata)
                    ?: _ggufFiles.value.singleOrNull { it.path == path }?.let { selected ->
                        buildDeterministicDiffusionBundleMetadata(
                            selected = listOf(selected),
                            componentMetadata = exactSetupComponentMetadata.values,
                            author = detail.author,
                            libraryName = detail.libraryName,
                            pipelineTag = detail.pipelineTag,
                        ).takeIf(List<DownloadMetadataDTO>::isNotEmpty)
                    }
                    ?: return null
                metadata.map { value ->
                    DownloadArtifactRequest(
                        metadata = value,
                        primary = value.artifact.repositoryId == ownerModelId,
                    )
                }
            }
        }
    }

    fun confirmDownloadForLater() {
        val pending = pendingDownloadForLater ?: return
        pendingDownloadForLater = null
        _showDownloadForLaterConfirmation.value = false
        when (pending) {
            is PendingDownloadForLater.Single -> startLanguageDownload(
                pending.modelId,
                pending.path,
                pending.metadata,
                downloadForLaterConfirmed = true,
            )
            is PendingDownloadForLater.Smart -> startSmartInstall(
                pending.modelId,
                requestedVariantPath = pending.variantPath,
                downloadForLaterConfirmed = true,
            )
            is PendingDownloadForLater.Group -> startLanguageBundleDownload(
                pending.modelId,
                pending.metadata,
                downloadForLaterConfirmed = true,
            )
        }
    }

    fun dismissDownloadForLater() {
        pendingDownloadForLater = null
        _showDownloadForLaterConfirmation.value = false
    }

    private fun requestDownloadForLaterConfirmation(pending: PendingDownloadForLater) {
        pendingDownloadForLater = pending
        _showDownloadForLaterConfirmation.value = true
    }

    private suspend fun refreshDownloadAdmission(
        modelId: String,
        metadata: DownloadMetadataDTO,
        requests: List<DownloadArtifactRequest>,
        offerDownloadForLater: Boolean = true,
        browseVariantIdentity: String? = null,
    ): DownloadAdmission {
        if (!isExactCurrentDetailArtifact(modelId, metadata.artifact)) {
            return DownloadAdmission.Blocked(
                com.debanshu777.caraml.core.recommendation.AssessmentReason.INVALID_METADATA,
            )
        }
        val state = _recommendedModels.value.firstOrNull { it.repositoryId == modelId }
        if (browseVariantIdentity != null && state?.browseVariants?.singleOrNull {
                it.stableIdentity == browseVariantIdentity &&
                    matchesExactBrowseGroup(it, requests.map { request -> request.metadata.artifact })
            } == null
        ) {
            return DownloadAdmission.Blocked(
                com.debanshu777.caraml.core.recommendation.AssessmentReason.INVALID_METADATA,
            )
        }
        if (state == null || state.descriptorState == DescriptorState.NEEDS_INFORMATION) {
            return DownloadAdmission.Allowed
        }
        val descriptor = state.selectedDescriptor
            ?: return DownloadAdmission.Allowed
        if (findExactTarget(descriptor, metadata.artifact) == null) {
            return DownloadAdmission.Allowed
        }
        val session = recommendationSession
            ?: return DownloadAdmission.Blocked(com.debanshu777.caraml.core.recommendation.AssessmentReason.MEMORY_BOUNDS_UNKNOWN)
        val refreshed = recommendationService.refreshForAdmission(
            session,
            state,
            settings.value.recommendationProfile,
        ) ?: return DownloadAdmission.Blocked(com.debanshu777.caraml.core.recommendation.AssessmentReason.RESOURCE_READING_UNAVAILABLE)
        _recommendedModels.update { current ->
            current.map { if (it.sourceIndex == refreshed.sourceIndex) refreshed else it }
        }
        if (browseVariantIdentity != null && refreshed.browseVariants.singleOrNull {
                it.stableIdentity == browseVariantIdentity &&
                    matchesExactBrowseGroup(it, requests.map { request -> request.metadata.artifact })
            } == null
        ) {
            return DownloadAdmission.Blocked(
                com.debanshu777.caraml.core.recommendation.AssessmentReason.INVALID_METADATA,
            )
        }
        val refreshedDescriptor = refreshed.selectedDescriptor
            ?: return DownloadAdmission.Allowed
        if (findExactTarget(refreshedDescriptor, metadata.artifact) == null) {
            return DownloadAdmission.Allowed
        }
        when (estimateCurrentStorage(requests)) {
            is StorageRequirement.Ready -> Unit
            is StorageRequirement.Blocked -> return DownloadAdmission.Blocked(
                com.debanshu777.caraml.core.recommendation.AssessmentReason.INSUFFICIENT_STORAGE,
            )
            is StorageRequirement.NeedsInformation -> if (
                refreshed.personalizedResult?.category !=
                com.debanshu777.caraml.core.recommendation.RecommendationCategory.NEEDS_INFORMATION
            ) {
                return DownloadAdmission.Blocked(
                    com.debanshu777.caraml.core.recommendation.AssessmentReason.STORAGE_BOUNDS_UNKNOWN,
                )
            }
        }
        val recommendation = refreshed.personalizedResult
            ?: return DownloadAdmission.Blocked(com.debanshu777.caraml.core.recommendation.AssessmentReason.MEMORY_BOUNDS_UNKNOWN)
        return downloadAdmissionPolicy.decide(recommendation, offerDownloadForLater)
    }

    private fun isExactCurrentDetailArtifact(
        modelId: String,
        artifact: DownloadArtifactIdentity,
    ): Boolean {
        val detailModelId = _modelDetail.value?.let { it.modelId ?: it.id }
        if (detailModelId != modelId || artifact.repositoryId != modelId) return false
        return _ggufFiles.value.singleOrNull { file ->
            file.path == artifact.relativePath && file.artifact == artifact
        } != null
    }

    private fun findExactTarget(
        descriptor: ModelDescriptor,
        artifact: DownloadArtifactIdentity,
    ): ModelFileIdentity? = descriptorFiles(descriptor).singleOrNull { file ->
        val remoteObjectId = file.canonicalDownloadRemoteObjectId()
        file.repositoryId == artifact.repositoryId &&
            file.revision.lowercase() == artifact.immutableRevision &&
            file.path == artifact.relativePath &&
            file.sizeBytes == artifact.expectedBytes &&
            remoteObjectId?.lowercase() == artifact.remoteObjectId
    }

    private suspend fun estimateCurrentStorage(requests: List<DownloadArtifactRequest>): StorageRequirement {
        return try {
            val snapshots = downloadManager.inspectStorage(requests.map { it.metadata })
                ?: return StorageRequirement.NeedsInformation()
            val inventory = snapshots.map { snapshot ->
                LocalDownloadArtifact(
                    repositoryId = snapshot.repositoryId,
                    relativePath = snapshot.destinationRelativePath,
                    finalBytes = snapshot.targetBytes,
                    partBytes = snapshot.stagedBytes,
                    exactPublished = snapshot.exactPublished,
                )
            }
            val locations = requests.associate { request ->
                ArtifactStorageKey(
                    request.metadata.artifact.repositoryId,
                    request.metadata.destinationRelativePath,
                ) to
                    ArtifactStorageLocation(finalVolume = MODELS_VOLUME, temporaryVolume = MODELS_VOLUME)
            }
            downloadStorageEstimator.estimate(
                requests = requests,
                localInventory = LocalDownloadInventory(inventory),
                layout = DownloadStorageLayout(
                    volumes = mapOf(
                        MODELS_VOLUME to StorageVolume(storagePathProvider.getAvailableStorageBytes()),
                    ),
                    locations = locations,
                ),
            )
        } catch (_: Exception) {
            StorageRequirement.NeedsInformation()
        }
    }

    private fun descriptorFiles(descriptor: ModelDescriptor): List<ModelFileIdentity> =
        when (descriptor) {
            is LlmModelDescriptor -> descriptor.files
            is DiffusionModelDescriptor -> descriptor.components
                .filter { it.required || it.isPrimary }
                .map { it.file }
        }

    private fun selectedDescriptorFor(modelId: String): ModelDescriptor? =
        _recommendedModels.value.singleOrNull { it.repositoryId == modelId }?.selectedDescriptor

    private suspend fun refreshGgufFilesDownloadState(modelId: String, isDiffusion: Boolean) {
        if (isDiffusion) {
            refreshVerifiedDiffusionInstallation(modelId)
            return
        }
        val downloaded = localModelRepository.getDownloadedFilenames(modelId)
        _ggufFiles.update { list ->
            list.map { file ->
                file.copy(isDownloaded = file.filename in downloaded || file.path in downloaded, progress = null)
            }
        }
    }

    private fun selectDiffusionFilesToDownload(
        pending: List<GgufFileUiState>,
        triggeredPath: String,
    ): List<GgufFileUiState> {
        val isRootFile = !triggeredPath.contains('/')
        if (isRootFile) {
            return pending.filter { it.path == triggeredPath }
        }
        val wantFp16 = triggeredPath.contains(".fp16.", ignoreCase = true)
        return NATIVE_DIFFUSERS_CONSUMED_PATHS.sorted().mapNotNull { destination ->
            val candidates = pending.filter {
                normalizedDiffusersRelativePath(it.path) == destination
            }
            candidates.singleOrNull {
                it.path.contains(".fp16.", ignoreCase = true) == wantFp16
            } ?: candidates.singleOrNull()
        }
    }

    fun clearDownloadError() {
        _downloadError.update { null }
    }

    fun clearDetailError() {
        _detailError.update { null }
    }

    fun clearListError() {
        _listError.update { null }
    }

    fun updateSearchQuery(query: String) {
        if (_browseMode.value != ModelHubBrowseMode.LanguageModels) return
        if (_searchQuery.value == query) return
        _searchQuery.update { query }
        if (_results.value.inputError != null) _results.value = _results.value.copy(inputError = null)
        searchDebounceJob?.cancel()
        if (query.isEmpty()) {
            clearSearch()
        } else {
            searchDebounceJob = viewModelScope.launch {
                delay(500)
                commitSearch()
            }
        }
    }

    fun performSearch() {
        if (_browseMode.value != ModelHubBrowseMode.LanguageModels) return
        searchDebounceJob?.cancel()
        commitSearch()
    }

    private fun commitSearch() {
        if (_browseMode.value != ModelHubBrowseMode.LanguageModels) return
        val committed = _searchQuery.value.trim()
        if (committed.isEmpty()) {
            if (_searchQuery.value.isEmpty()) clearSearch() else {
                _searchError.value = "Please enter a search query"
                _results.value = _results.value.copy(inputError = _searchError.value)
            }
            return
        }
        if (committed.length > 128 || committed.any { it.code < 32 || it.code == 127 }) {
            _searchError.value = "Search text is too long or contains invalid characters."
            _results.value = _results.value.copy(inputError = _searchError.value)
            return
        }
        if (_results.value.key?.committedSearch == committed && _results.value.initialError == null) return
        startPageQuery(committed)
    }

    fun submitSearch() = performSearch()

    fun clearSearch() {
        if (_browseMode.value != ModelHubBrowseMode.LanguageModels) return
        searchDebounceJob?.cancel()
        _searchQuery.value = ""
        if (_results.value.key?.committedSearch == null && _results.value.key != null &&
            _results.value.initialError == null) return
        startPageQuery(null)
    }

    fun setRecommendationOrdering(ordering: RecommendationOrdering) {
        _recommendationOrdering.value = ordering
        recommendationSession?.let { session ->
            viewModelScope.launch { recommendationService.setOrdering(session, ordering) }
        }
    }

    fun setModelOrdering(ordering: ModelOrdering) {
        when (ordering) {
            ModelOrdering.Personalized -> {
                _modelOrdering.value = ordering
                setRecommendationOrdering(RecommendationOrdering.PERSONALIZED)
            }
            is ModelOrdering.Server -> {
                if (ordering.value !in SUPPORTED_REMOTE_SORTS) return
                val changed = _results.value.key?.remoteSort != ordering.value
                _modelOrdering.value = ordering
                updateParams(sort = ordering.value)
                setRecommendationOrdering(RecommendationOrdering.SERVER)
                if (changed) loadModels()
            }
        }
    }

    fun loadMoreRecommendations() {
        recommendationSession?.let { session ->
            if (session.evaluatedCount >= 96 || session.evaluatedCount >= session.candidates.size) return
            viewModelScope.launch {
                recommendationService.evaluateMore(session, settings.value.recommendationProfile)
            }
        }
    }

    private fun startRecommendations(
        models: List<ListModelsResponse.Model>,
        workload: WorkloadConfig,
        source: String = "list",
    ) {
        recommendationJob?.cancel()
        recommendationSession?.cancel()
        val queryId = "${_browseMode.value.name}:$source"
        val session = recommendationService.startQuery(queryId, models, workload)
        recommendationSession = session
        _recommendedModels.value = session.state.value
        recommendationJob = viewModelScope.launch {
            coroutineScope {
                launch { session.state.collectLatest { _recommendedModels.value = it } }
                launch {
                    recommendationService.setOrdering(session, _recommendationOrdering.value)
                    recommendationService.evaluateInitial(session, settings.value.recommendationProfile)
                    recommendationService.rerank(session, settings.value.recommendationProfile)
                }
            }
        }
    }

    private fun clearRecommendations() {
        recommendationAppendJob?.cancel()
        recommendationAppendJob = null
        recommendationJob?.cancel()
        recommendationSession?.cancel()
        recommendationJob = null
        recommendationSession = null
        _recommendedModels.value = emptyList()
    }

    private fun beginModelRequest(): Any {
        val owner = Any()
        activeModelRequestOwner = owner
        modelPager = null
        listRequestJob?.cancel()
        searchRequestJob?.cancel()
        listRequestJob = null
        searchRequestJob = null
        _isListLoading.value = false
        _isSearchLoading.value = false
        clearRecommendations()
        return owner
    }

    private fun invalidateModelRequests() {
        searchDebounceJob?.cancel()
        lastAutoRequestedCursor = null
        activeModelRequestOwner = Any()
        modelPager = null
        listRequestJob?.cancel()
        searchRequestJob?.cancel()
        listRequestJob = null
        searchRequestJob = null
        _isListLoading.value = false
        _isSearchLoading.value = false
        clearRecommendations()
    }

    private fun ownsModelRequest(owner: Any): Boolean = activeModelRequestOwner === owner

    private fun ownsSearchRequest(owner: Any, query: String): Boolean =
        ownsModelRequest(owner) &&
            _browseMode.value == ModelHubBrowseMode.LanguageModels &&
            _searchQuery.value == query

    private fun resetSearchStateForCuratedHub() {
        searchDebounceJob?.cancel()
        _searchQuery.update { "" }
        _searchResponse.update { null }
        _searchError.update { null }
        _isSearchLoading.update { false }
    }

    override fun onCleared() {
        super.onCleared()
        listRequestJob?.cancel()
        searchRequestJob?.cancel()
        recommendationJob?.cancel()
        recommendationAppendJob?.cancel()
        calibrationJob?.cancel()
        recommendationSession?.cancel()
        listRequestJob = null
        searchRequestJob = null
        recommendationJob = null
        calibrationJob = null
        recommendationSession = null
    }

    fun runQuickCalibration(allowUnknownPower: Boolean = false) {
        val calibration = quickCalibrationRunner ?: return
        if (calibrationJob?.isActive == true) return
        _quickCalibration.value = QuickCalibrationUiState(running = true)
        calibrationJob = viewModelScope.launch {
            try {
                _quickCalibration.value = QuickCalibrationUiState(
                    result = calibration.runQuickCalibration(allowUnknownPower),
                )
            } catch (cancelled: CancellationException) {
                _quickCalibration.value = QuickCalibrationUiState(
                    result = if (calibration.nativeCalibrationState() ==
                        com.debanshu777.caraml.core.recommendation.NativeCalibrationState.QUARANTINED
                    ) CalibrationRunResult.Quarantined else CalibrationRunResult.Cancelled,
                )
                throw cancelled
            }
        }
    }

    fun skipQuickCalibration() {
        val calibration = quickCalibrationRunner ?: return
        calibrationJob?.cancel()
        calibrationJob = viewModelScope.launch {
            try {
                calibration.skipQuickCalibration()
                _quickCalibration.value = QuickCalibrationUiState()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _quickCalibration.value = QuickCalibrationUiState(
                    result = CalibrationRunResult.Failed,
                )
            }
        }
    }

    fun cancelQuickCalibration() {
        calibrationJob?.cancel()
    }

    fun clearSearchError() {
        _searchError.update { null }
    }

    private suspend fun loadSetupComponentsForModel(modelId: String) {
        val setup = getModelSetup(modelId) ?: return
        val metadata = LinkedHashMap<SdCppComponent, DownloadMetadataDTO>()
        val requiredComponents = setup.components.filter { it.required }
        val installedByRepository = requiredComponents
            .map(SdCppComponent::repoId)
            .distinct()
            .associateWith { downloadManager.validatedArtifacts(it)?.entries.orEmpty() }
        for (component in requiredComponents) {
            val installed = findInstalledSetupComponent(
                installedByRepository[component.repoId].orEmpty(),
                component,
            )
            val installedMetadata = installed?.let { entry ->
                DownloadMetadataDTO(
                    artifact = entry.identity,
                    logicalRole = entry.logicalRole,
                    sizeBytes = entry.identity.expectedBytes,
                    author = _modelDetail.value?.author,
                    libraryName = "stable-diffusion.cpp",
                    pipelineTag = _modelDetail.value?.pipelineTag,
                    contextLength = null,
                    destinationRelativePath = entry.localRelativePath,
                    bundleId = entry.bundleId,
                )
            }
            (installedMetadata ?: runCatching { createMetadataForComponent(component) }.getOrNull())
                ?.let { metadata[component] = it }
        }
        exactSetupComponentMetadata = metadata
        _setupComponents.update {
            setup.components.map { component ->
                SetupComponentUiState(
                    role = component.role,
                    repoId = component.repoId,
                    filePath = component.filePath,
                    sizeHint = component.sizeHint,
                    isDownloaded = false,
                    progress = null,
                    required = component.required,
                    sharedFrom = null,
                )
            }
        }
        refreshVerifiedDiffusionInstallation(modelId)
    }

    private suspend fun refreshVerifiedDiffusionInstallation(modelId: String) {
        _validatedBundleReady.value = false
        val requiredComponents = getModelSetup(modelId)?.components?.filter { it.required }.orEmpty()
        val metadataComplete = requiredComponents.all { it in exactSetupComponentMetadata }
        val repositoryIds = buildSet {
            add(modelId)
            exactSetupComponentMetadata.values.forEach { add(it.artifact.repositoryId) }
        }
        val artifactManifests = repositoryIds.associateWith { downloadManager.validatedArtifacts(it) }
        val individuallyInstalled = artifactManifests.values
            .filterNotNull()
            .flatMap { it.entries }
        if (!metadataComplete) {
            _ggufFiles.update { files -> files.map { it.copy(isDownloaded = false, progress = null) } }
            _setupComponents.update { components ->
                components.map { it.copy(isDownloaded = false, progress = null) }
            }
            return
        }
        val candidates = _ggufFiles.value.asSequence()
            .mapNotNull { it.path.takeIf(String::isNotBlank) }
            .mapNotNull { createExactBundleMetadata(it, exactSetupComponentMetadata) }
            .distinctBy { it.first().bundleId }
            .toList()
        val aggregate = downloadManager.validatedBundle(modelId)
        val aggregateMetadata = aggregate?.let { manifest ->
            candidates.singleOrNull(manifest::matchesExactBundle)
        }
        val recovery = if (aggregateMetadata == null) {
            recoverInterruptedDiffusionBundle(candidates, individuallyInstalled)
        } else {
            InterruptedDiffusionBundleRecovery(aggregateMetadata, aggregateMetadata)
        }
        if (recovery == null) {
            _ggufFiles.update { files -> files.map { it.copy(isDownloaded = false, progress = null) } }
            _setupComponents.update { components ->
                components.map { it.copy(isDownloaded = false, progress = null) }
            }
            return
        }
        val installedMetadata = recovery.installedMetadata.toSet()
        _ggufFiles.update { files ->
            files.map { file ->
                val expected = recovery.metadata.singleOrNull { it.artifact == file.artifact }
                file.copy(isDownloaded = expected in installedMetadata, progress = null)
            }
        }
        _setupComponents.update { components ->
            components.map { state ->
                val expected = exactSetupComponentMetadata.entries.singleOrNull { (component) ->
                    component.repoId == state.repoId && component.filePath == state.filePath
                }?.value?.let { source ->
                    recovery.metadata.singleOrNull { it.artifact == source.artifact }
                }
                state.copy(isDownloaded = expected in installedMetadata, progress = null)
            }
        }
        _selectedVariantPath.value = recovery.metadata.single { it.logicalRole == "model" }.artifact.relativePath
        _validatedBundleReady.value = aggregateMetadata != null
    }

    private fun createExactBundleMetadata(
        triggeredPath: String,
        componentMetadata: Map<SdCppComponent, DownloadMetadataDTO>,
    ): List<DownloadMetadataDTO>? {
        val selected = selectDiffusionFilesToDownload(
            _ggufFiles.value.filter { it.path.isNotBlank() },
            triggeredPath,
        )
        if (selected.isEmpty() || selected.none { it.path == triggeredPath }) return null
        val detail = _modelDetail.value
        return buildDeterministicDiffusionBundleMetadata(
            selected = selected,
            componentMetadata = componentMetadata.values,
            author = detail?.author,
            libraryName = detail?.libraryName,
            pipelineTag = detail?.pipelineTag,
        ).takeIf(List<DownloadMetadataDTO>::isNotEmpty)
    }

    private suspend fun createMetadataForComponent(component: SdCppComponent): DownloadMetadataDTO {
        val detail = _modelDetail.value
        val componentDetail = when (val result = api.getModelDetail(component.repoId)) {
            is Result.Success -> result.data
            is Result.Error -> throw IllegalStateException("Exact artifact identity is unavailable")
        }
        val revision = componentDetail.sha
            ?: throw IllegalStateException("Exact artifact identity is unavailable")
        val tree = when (
            val result = api.getModelFileTree(
                component.repoId,
                revision,
                ModelFileWeightFilter.StableDiffusionCppWeights,
            )
        ) {
            is Result.Success -> result.data
            is Result.Error -> throw IllegalStateException("Exact artifact identity is unavailable")
        }
        val remote = tree.singleOrNull { it.path == component.filePath }
            ?: throw IllegalStateException("Exact artifact identity is unavailable")
        val expectedBytes = remote.lfs?.size ?: remote.size
            ?: throw IllegalStateException("Exact artifact identity is unavailable")
        val identity = DownloadArtifactIdentity.create(
            repositoryId = component.repoId,
            immutableRevision = revision,
            relativePath = component.filePath,
            remoteObjectId = remote.lfs?.oid?.let { "sha256:$it" } ?: remote.xetHash ?: remote.oid,
            expectedBytes = expectedBytes,
        ) ?: throw IllegalStateException("Exact artifact identity is unavailable")
        return DownloadMetadataDTO(
            artifact = identity,
            logicalRole = component.role.name.lowercase(),
            sizeBytes = expectedBytes,
            author = detail?.author,
            libraryName = "stable-diffusion.cpp",
            pipelineTag = detail?.pipelineTag,
            contextLength = null,
        )
    }

    private fun parseSizeHint(sizeHint: String): Long? = parseSizeHintToBytes(sizeHint)

    fun clearSetupDownloadError() {
        _setupDownloadError.update { null }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex++
        }
        val display = if (value >= 100 || unitIndex == 0) {
            value.toInt().toString()
        } else {
            val rounded = round(value * 10.0) / 10.0
            if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
        }
        return "$display ${units[unitIndex]}"
    }
}
