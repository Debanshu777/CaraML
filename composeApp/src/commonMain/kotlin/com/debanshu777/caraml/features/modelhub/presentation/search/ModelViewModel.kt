package com.debanshu777.caraml.features.modelhub.presentation.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.debanshu777.caraml.core.data.settings.SettingsRepository
import com.debanshu777.caraml.core.recommendation.DiffusionMode
import com.debanshu777.caraml.core.recommendation.DiffusionWorkloadConfig
import com.debanshu777.caraml.core.recommendation.KvCacheSelection
import com.debanshu777.caraml.core.recommendation.KvCacheType
import com.debanshu777.caraml.core.recommendation.LlmWorkloadConfig
import com.debanshu777.caraml.core.recommendation.LlmModelDescriptor
import com.debanshu777.caraml.core.recommendation.DiffusionModelDescriptor
import com.debanshu777.caraml.core.recommendation.ModelDescriptor
import com.debanshu777.caraml.core.recommendation.ModelFileIdentity
import com.debanshu777.caraml.core.recommendation.WorkloadConfig
import com.debanshu777.caraml.core.storage.component.ComponentRepository
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.core.storage.localmodel.LocalModelRepository
import com.debanshu777.caraml.core.storage.localmodel.ModelType
import com.debanshu777.caraml.core.platform.DeviceCapabilities
import com.debanshu777.caraml.core.platform.DeviceHints
import com.debanshu777.caraml.core.rating.parseSizeHintToBytes
import com.debanshu777.caraml.features.modelhub.domain.ModelRecommendationService
import com.debanshu777.caraml.features.modelhub.domain.RecommendationOrdering
import com.debanshu777.caraml.features.modelhub.domain.RecommendationQuerySession
import com.debanshu777.caraml.features.modelhub.domain.RecommendedModelUiState
import com.debanshu777.caraml.features.modelhub.domain.QuerySupersededCancellationException
import com.debanshu777.caraml.features.modelhub.domain.DownloadAdmission
import com.debanshu777.caraml.features.modelhub.domain.DownloadAdmissionPolicy
import com.debanshu777.caraml.features.modelhub.domain.DownloadAdmissionRejected
import com.debanshu777.caraml.features.modelhub.domain.downloadAdmissionErrorMessage
import com.debanshu777.caraml.features.modelhub.domain.requireForComponent
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
import com.debanshu777.huggingfacemanager.api.SearchModelsParams
import com.debanshu777.huggingfacemanager.api.error.DataError
import com.debanshu777.huggingfacemanager.api.error.Result
import com.debanshu777.huggingfacemanager.download.DownloadManager
import com.debanshu777.huggingfacemanager.download.DownloadArtifactIdentity
import com.debanshu777.huggingfacemanager.download.DownloadMetadataDTO
import com.debanshu777.huggingfacemanager.download.ArtifactVerificationException
import com.debanshu777.huggingfacemanager.download.ArtifactManifest
import com.debanshu777.huggingfacemanager.download.artifactBundleId
import com.debanshu777.huggingfacemanager.download.IncompleteDownloadException
import com.debanshu777.huggingfacemanager.download.InsufficientStorageException
import com.debanshu777.huggingfacemanager.download.StoragePathProvider
import com.debanshu777.huggingfacemanager.model.DIFFUSERS_BUNDLE_DB_FILENAME
import com.debanshu777.huggingfacemanager.model.ModelDetailResponse
import com.debanshu777.huggingfacemanager.model.ModelFileWeightFilter
import com.debanshu777.huggingfacemanager.model.isDiffusersModelDirectory
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
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

data class GgufFileUiState(
    val path: String,
    val filename: String,
    val sizeBytes: Long?,
    val isDownloaded: Boolean,
    val progress: Float?,
    val artifact: DownloadArtifactIdentity? = null,
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
                installed.localRelativePath == expected.destinationRelativePath
        } != null
    }

data class StorageInfoUiState(
    val totalDeviceBytes: Long = 0L,
    val availableDeviceBytes: Long = 0L,
    val usedByModelsBytes: Long = 0L,
    val deviceHints: DeviceHints? = null,
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
)

/** Internal snapshot of ongoing install progress, updated on every progress tick. */
private data class InstallProgress(
    val fraction: Float? = null,
    val bytesReceived: Long = 0L,
    val bytesTotal: Long = 0L,
    val label: String? = null,
)

private sealed interface PendingDownloadForLater {
    data class Single(
        val modelId: String,
        val path: String,
        val metadata: DownloadMetadataDTO,
    ) : PendingDownloadForLater

    data class Smart(val modelId: String, val variantPath: String) : PendingDownloadForLater

    data class Repair(val modelId: String) : PendingDownloadForLater
}

private fun defaultRecommendationWorkload(mode: ModelHubBrowseMode): WorkloadConfig = when (mode) {
    ModelHubBrowseMode.LanguageModels -> LlmWorkloadConfig(
        userRequestedContextTokens = 4_096,
        contextTokens = 4_096,
        minimumContextTokens = 512,
        promptTokens = 256,
        generationReserveTokens = 256,
        batchSize = 256,
        microBatchSize = 64,
        sequenceCount = 1,
        kvCacheSelection = KvCacheSelection.Auto,
        allowContextFallback = true,
        allowBatchFallback = true,
        allowKvCacheFallback = true,
        allowedKvCacheTypes = KvCacheType.entries,
        evidence = emptyList(),
    )
    ModelHubBrowseMode.DiffusionImage,
    ModelHubBrowseMode.DiffusionVideo,
    -> DiffusionWorkloadConfig(
        mode = if (mode == ModelHubBrowseMode.DiffusionVideo) DiffusionMode.VIDEO else DiffusionMode.IMAGE,
        width = 1_024,
        height = if (mode == ModelHubBrowseMode.DiffusionVideo) 576 else 1_024,
        minimumWidth = 512,
        minimumHeight = 512,
        frameCount = if (mode == ModelHubBrowseMode.DiffusionVideo) 16 else 1,
        minimumFrameCount = 1,
        batchSize = 1,
        steps = 20,
        vaeTiling = false,
        offloadToCpu = false,
        keepClipOnCpu = false,
        keepVaeOnCpu = false,
        maxVramBytes = null,
        layerStreaming = false,
        allowResolutionFallback = true,
        allowFrameCountFallback = mode == ModelHubBrowseMode.DiffusionVideo,
        allowVaeTilingFallback = true,
        allowMaxVramFallback = true,
        allowLayerStreamingFallback = true,
        evidence = emptyList(),
    )
}

class ModelViewModel(
    private val api: HuggingFaceApi,
    private val localModelRepository: LocalModelRepository,
    private val componentRepository: ComponentRepository,
    private val downloadManager: DownloadManager,
    private val storagePathProvider: StoragePathProvider,
    private val deviceCapabilities: DeviceCapabilities,
    private val recommendationService: ModelRecommendationService,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val downloadAdmissionPolicy = DownloadAdmissionPolicy()
    private val downloadStorageEstimator = DownloadStorageEstimator()
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
    private var listRequestJob: Job? = null
    private var searchRequestJob: Job? = null
    @Volatile private var activeModelRequestOwner: Any = Any()

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
    }

    val storageInfo: StateFlow<StorageInfoUiState> =
        localModelRepository.getTotalDownloadedSizeBytes()
            .map { usedBytes ->
                StorageInfoUiState(
                    totalDeviceBytes = storagePathProvider.getTotalStorageBytes(),
                    availableDeviceBytes = storagePathProvider.getAvailableStorageBytes(),
                    usedByModelsBytes = usedBytes,
                    deviceHints = deviceCapabilities.getDeviceHints(),
                )
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = StorageInfoUiState()
            )

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

    private val _downloadError = MutableStateFlow<String?>(null)
    val downloadError: StateFlow<String?> = _downloadError.asStateFlow()

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

    /** Cumulative bytes from files already completed in the current install pass. */
    @Volatile private var _installBytesCompleted: Long = 0L
    /** Total expected bytes for the current install pass (variant + components). */
    @Volatile private var _installBytesTotal: Long = 0L

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
                startRecommendations(response.models.orEmpty().filterNotNull(), defaultRecommendationWorkload(mode))
            }

            ModelHubBrowseMode.DiffusionVideo -> {
                resetSearchStateForCuratedHub()
                _listError.update { null }
                _isListLoading.update { false }
                val response = sdCppCatalog.video.toVideoListModelsResponse()
                _listResponse.update { response }
                startRecommendations(response.models.orEmpty().filterNotNull(), defaultRecommendationWorkload(mode))
            }
        }
    }

    fun loadModels() {
        if (_browseMode.value != ModelHubBrowseMode.LanguageModels) return
        val params = _listParams.value
        val owner = beginModelRequest()
        listRequestJob = viewModelScope.launch {
            _isListLoading.update { true }
            _listError.update { null }
            when (val result = api.listModels(params)) {
                is Result.Success -> {
                    if (!ownsModelRequest(owner) || _browseMode.value != ModelHubBrowseMode.LanguageModels) {
                        return@launch
                    }
                    _listResponse.update { result.data }
                    _listError.update { null }
                    startRecommendations(
                        result.data.models.orEmpty().filterNotNull(),
                        defaultRecommendationWorkload(ModelHubBrowseMode.LanguageModels),
                    )
                }
                is Result.Error -> {
                    if (!ownsModelRequest(owner) || _browseMode.value != ModelHubBrowseMode.LanguageModels) {
                        return@launch
                    }
                    _listError.update {
                        when (result.error) {
                            DataError.Network.NoInternet ->
                                "No internet connection. Please check your network and try again."
                            DataError.Network.Serialization ->
                                "Failed to process server response. The data format may be invalid."
                            DataError.Network.Unauthorized ->
                                "Authentication failed. Please check your credentials."
                            DataError.Network.RequestTimeout ->
                                "Request timed out. The server took too long to respond."
                            DataError.Network.Conflict ->
                                "Request conflict. Please refresh and try again."
                            DataError.Network.PayloadTooLarge ->
                                "Request too large. Try adjusting your filters."
                            DataError.Network.ServerError ->
                                "Server error occurred. Please try again later."
                            DataError.Network.Unknown ->
                                "An unexpected error occurred. Please try again."
                        }
                    }
                }
            }
            if (ownsModelRequest(owner)) _isListLoading.update { false }
        }
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

    fun loadDetail(
        modelId: String,
        hubBrowseMode: ModelHubBrowseMode = ModelHubBrowseMode.LanguageModels,
    ) {
        if (modelId.isBlank()) return
        _browseMode.value = hubBrowseMode
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
                            DataError.Network.RequestTimeout ->
                                "Request timed out. The server took too long to respond."
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
                _installBytesTotal = ownedArtifacts
                    .filter { it.artifact in missingPrimaryIdentities || it.artifact in missingComponentIdentities }
                    .sumOf { it.artifact.expectedBytes }
                _installBytesCompleted = 0L
                _installProgress.update { InstallProgress(bytesTotal = _installBytesTotal) }

                val selectedMetadata = primaryMetadata.singleOrNull { it.artifact.relativePath == variantPath }
                    ?: throw IllegalStateException("Exact artifact identity is unavailable")

                when (val admission = refreshDownloadAdmission(modelId, selectedMetadata)) {
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

                downloadDiffusionBundle(
                    modelId = modelId,
                    triggeredPath = variantPath,
                    ownedMetadata = primaryMetadata,
                    downloadForLaterConfirmed = downloadForLaterConfirmed,
                )
                downloadMissingComponents(modelId, downloadForLaterConfirmed, ownedComponentMetadata)
                if (!downloadManager.publishBundle(modelId, ownedArtifacts) ||
                    !downloadManager.validateBundle(modelId, ownedArtifacts)
                ) throw ArtifactVerificationException()
                persistDiffusionModelRecord(modelId, primaryMetadata, modelType)
                refreshVerifiedDiffusionInstallation(modelId)
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
            } catch (_: Exception) {
                _downloadError.update { "Install failed. Please check your connection and try again." }
                _ggufFiles.update { list -> list.map { it.copy(progress = null) } }
                _setupComponents.update { list -> list.map { it.copy(progress = null) } }
            } finally {
                _isDownloading.update { false }
                _installProgress.update { InstallProgress() }
            }
        }
    }

    /** Legacy entry point — kept for language models; for diffusion models delegates to smartInstall. */
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
            try {
                when (val admission = refreshDownloadAdmission(modelId, metadata)) {
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
                downloadSingleWeight(modelId, path, metadata, modelType = ModelType.TEXT)
                refreshGgufFilesDownloadState(modelId, isDiffusion = false)
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
            } catch (_: Exception) {
                _downloadError.update { "Download failed. Please check your connection and try again." }
                _ggufFiles.update { list -> list.map { it.copy(progress = null) } }
            } finally {
                _isDownloading.update { false }
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
            is PendingDownloadForLater.Repair -> startSetupComponentDownload(
                pending.modelId,
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
        offerDownloadForLater: Boolean = true,
    ): DownloadAdmission {
        val session = recommendationSession
            ?: return DownloadAdmission.Blocked(com.debanshu777.caraml.core.recommendation.AssessmentReason.MEMORY_BOUNDS_UNKNOWN)
        val state = _recommendedModels.value.firstOrNull { it.repositoryId == modelId }
            ?: return DownloadAdmission.Blocked(com.debanshu777.caraml.core.recommendation.AssessmentReason.MEMORY_BOUNDS_UNKNOWN)
        val descriptor = state.selectedDescriptor
            ?: return DownloadAdmission.Blocked(com.debanshu777.caraml.core.recommendation.AssessmentReason.MEMORY_BOUNDS_UNKNOWN)
        if (findExactTarget(descriptor, metadata.artifact) == null) {
            return DownloadAdmission.Blocked(com.debanshu777.caraml.core.recommendation.AssessmentReason.INVALID_METADATA)
        }
        val refreshed = recommendationService.refreshForAdmission(
            session,
            state,
            settings.value.recommendationProfile,
        ) ?: return DownloadAdmission.Blocked(com.debanshu777.caraml.core.recommendation.AssessmentReason.RESOURCE_READING_UNAVAILABLE)
        val refreshedDescriptor = refreshed.selectedDescriptor
            ?: return DownloadAdmission.Blocked(com.debanshu777.caraml.core.recommendation.AssessmentReason.MEMORY_BOUNDS_UNKNOWN)
        if (findExactTarget(refreshedDescriptor, metadata.artifact) == null) {
            return DownloadAdmission.Blocked(com.debanshu777.caraml.core.recommendation.AssessmentReason.INVALID_METADATA)
        }
        _recommendedModels.update { current ->
            current.map { if (it.sourceIndex == refreshed.sourceIndex) refreshed else it }
        }
        when (estimateCurrentStorage(refreshedDescriptor)) {
            is StorageRequirement.Ready -> Unit
            is StorageRequirement.Blocked -> return DownloadAdmission.Blocked(
                com.debanshu777.caraml.core.recommendation.AssessmentReason.INSUFFICIENT_STORAGE,
            )
            is StorageRequirement.NeedsInformation -> return DownloadAdmission.Blocked(
                com.debanshu777.caraml.core.recommendation.AssessmentReason.STORAGE_BOUNDS_UNKNOWN,
            )
        }
        val recommendation = refreshed.personalizedResult
            ?: return DownloadAdmission.Blocked(com.debanshu777.caraml.core.recommendation.AssessmentReason.MEMORY_BOUNDS_UNKNOWN)
        return downloadAdmissionPolicy.decide(recommendation, offerDownloadForLater)
    }

    private fun findExactTarget(
        descriptor: ModelDescriptor,
        artifact: DownloadArtifactIdentity,
    ): ModelFileIdentity? = descriptorFiles(descriptor).singleOrNull { file ->
        val remoteObjectId = file.lfsOid?.let { "sha256:$it" } ?: file.xetHash ?: file.gitOid
        file.repositoryId == artifact.repositoryId &&
            file.revision.lowercase() == artifact.immutableRevision &&
            file.path == artifact.relativePath &&
            file.sizeBytes == artifact.expectedBytes &&
            remoteObjectId?.lowercase() == artifact.remoteObjectId
    }

    private fun estimateCurrentStorage(descriptor: ModelDescriptor): StorageRequirement {
        val files = descriptorFiles(descriptor)
        return try {
            fun existingBytes(path: String): Long? =
                if (storagePathProvider.fileExists(path)) storagePathProvider.getFileSize(path) else null
            val inventory = files.map { file ->
                val root = storagePathProvider.getModelsStorageDirectory(file.repositoryId)
                val finalPath = "$root/${file.path}"
                val partPath = "$finalPath.part"
                LocalDownloadArtifact(
                    repositoryId = file.repositoryId,
                    relativePath = file.path,
                    finalBytes = existingBytes(finalPath),
                    partBytes = existingBytes(partPath),
                )
            }
            val locations = files.associate { file ->
                ArtifactStorageKey(file.repositoryId, file.path) to
                    ArtifactStorageLocation(finalVolume = MODELS_VOLUME, temporaryVolume = MODELS_VOLUME)
            }
            downloadStorageEstimator.estimate(
                descriptor = descriptor,
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

    private suspend fun downloadSingleWeight(
        modelId: String,
        path: String,
        metadata: DownloadMetadataDTO,
        modelType: String,
    ) {
        downloadManager.download(modelId, path, metadata).collect { progress ->
            _ggufFiles.update { list ->
                list.map {
                    if (it.path == path) it.copy(progress = progress.percentage) else it
                }
            }
            progress.localPath?.let { completedPath ->
                if (!downloadManager.publishBundle(modelId, listOf(metadata)) ||
                    !downloadManager.validateBundle(modelId, listOf(metadata))
                ) throw ArtifactVerificationException()
                val relativePath = path.trim().replace('\\', '/').trimStart('/')
                val filename = relativePath.substringAfterLast('/').ifEmpty { relativePath }
                localModelRepository.insert(
                    modelId = modelId,
                    filename = filename,
                    localPath = completedPath,
                    sizeBytes = metadata.sizeBytes,
                    author = metadata.author,
                    libraryName = metadata.libraryName,
                    pipelineTag = metadata.pipelineTag,
                    contextLength = metadata.contextLength,
                    modelType = modelType,
                    isMainModel = !filename.contains("mmproj", ignoreCase = true),
                )
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
        val subdirFiles = pending.filter { it.path.contains('/') }
        val byDir = subdirFiles.groupBy { it.path.substringBeforeLast('/') }
        return byDir.flatMap { (_, filesInDir) ->
            val matching = filesInDir.filter {
                it.path.contains(".fp16.", ignoreCase = true) == wantFp16
            }
            matching.ifEmpty { filesInDir }
        }
    }

    private suspend fun downloadDiffusionBundle(
        modelId: String,
        triggeredPath: String,
        ownedMetadata: List<DownloadMetadataDTO>,
        downloadForLaterConfirmed: Boolean = false,
    ): List<DownloadMetadataDTO> {
        val allFiles = _ggufFiles.value.filter { it.path.isNotBlank() }
        val selected = selectDiffusionFilesToDownload(allFiles, triggeredPath)
        if (selected.isEmpty()) return emptyList()
        val pending = selected.filter { !it.isDownloaded }
        val totalRequired = pending.sumOf { it.sizeBytes ?: 0L }
        if (totalRequired > 0L) {
            val available = storagePathProvider.getAvailableStorageBytes()
            if (available < totalRequired) {
                throw InsufficientStorageException(totalRequired, available)
            }
        }

        val ordered = if (pending.any { it.path == triggeredPath }) {
            listOf(pending.first { it.path == triggeredPath }) + pending.filter { it.path != triggeredPath }
        } else {
            pending
        }

        val orderedMetadata = ordered.map { file ->
            file to ownedMetadata.single { it.artifact == file.artifact }
        }
        for ((file, meta) in orderedMetadata) {
            refreshDownloadAdmission(modelId, meta).requireForComponent(downloadForLaterConfirmed)
            val bytesBeforeThisFile = _installBytesCompleted
            downloadManager.download(modelId, file.path, meta).collect { progress ->
                _ggufFiles.update { list ->
                    list.map {
                        if (it.path == file.path) it.copy(progress = progress.percentage) else it
                    }
                }
                val overallReceived = bytesBeforeThisFile + progress.bytesReceived
                _installProgress.update {
                    InstallProgress(
                        fraction = if (_installBytesTotal > 0) overallReceived.toFloat() / _installBytesTotal else null,
                        bytesReceived = overallReceived,
                        bytesTotal = _installBytesTotal,
                        label = file.filename,
                    )
                }
            }
            _installBytesCompleted += file.sizeBytes ?: 0L
        }

        return ownedMetadata
    }

    private suspend fun persistDiffusionModelRecord(
        modelId: String,
        primaryMetadata: List<DownloadMetadataDTO>,
        modelType: String,
    ) {
        if (primaryMetadata.isEmpty()) throw ArtifactVerificationException()
        val modelRoot = storagePathProvider.getModelsStorageDirectory(modelId)
        val detail = _modelDetail.value
        val diffusersOk = isDiffusersModelDirectory(modelRoot, storagePathProvider::fileExists)
        val fallback = primaryMetadata.last()
        val localRelativePath = fallback.destinationRelativePath
        val localPath = "$modelRoot/$localRelativePath"
        if (!diffusersOk && !storagePathProvider.fileExists(localPath)) throw ArtifactVerificationException()
        localModelRepository.deleteAllForModelId(modelId)
        localModelRepository.insert(
            modelId = modelId,
            filename = if (diffusersOk) DIFFUSERS_BUNDLE_DB_FILENAME else localRelativePath.substringAfterLast('/'),
            localPath = if (diffusersOk) modelRoot else localPath,
            sizeBytes = primaryMetadata.sumOf { it.artifact.expectedBytes }.takeIf { it > 0L },
            author = detail?.author,
            libraryName = detail?.libraryName,
            pipelineTag = detail?.pipelineTag,
            contextLength = null,
            modelType = modelType,
            componentStatus = LocalModelEntity.STATUS_READY,
            isMainModel = true,
        )
    }

    /**
     * Downloads all missing required components for the given model.
     * Already-on-disk components are skipped. Records each download in ComponentRepository
     * and marks the model as "ready" when done.
     */
    private suspend fun downloadMissingComponents(
        modelId: String,
        downloadForLaterConfirmed: Boolean = false,
        preparedMetadata: Map<SdCppComponent, DownloadMetadataDTO> = emptyMap(),
    ): List<DownloadMetadataDTO> {
        val setup = getModelSetup(modelId)
        if (setup == null || setup.selfContained) {
            return emptyList()
        }

        val allRequired = setup.components.filter { it.required }
        val allMetadata = LinkedHashMap<SdCppComponent, DownloadMetadataDTO>()
        for (component in allRequired) {
            allMetadata[component] = preparedMetadata[component] ?: createMetadataForComponent(component)
        }
        val missingComponents = allRequired.filter { component ->
            _setupComponents.value.singleOrNull {
                it.repoId == component.repoId && it.filePath == component.filePath
            }?.isDownloaded != true
        }

        for (component in missingComponents) {
            val meta = requireNotNull(allMetadata[component])
            refreshDownloadAdmission(modelId, meta).requireForComponent(downloadForLaterConfirmed)
            val bytesBeforeThisComponent = _installBytesCompleted
            val componentLabel = component.filePath.substringAfterLast('/')
            downloadManager.download(component.repoId, component.filePath, meta).collect { progress ->
                _setupComponents.update { list ->
                    list.map {
                        if (it.repoId == component.repoId && it.filePath == component.filePath) {
                            it.copy(progress = progress.percentage)
                        } else it
                    }
                }
                val overallReceived = bytesBeforeThisComponent + progress.bytesReceived
                _installProgress.update {
                    InstallProgress(
                        fraction = if (_installBytesTotal > 0) overallReceived.toFloat() / _installBytesTotal else null,
                        bytesReceived = overallReceived,
                        bytesTotal = _installBytesTotal,
                        label = componentLabel,
                    )
                }
                if (progress.localPath != null) {
                    val compId = componentRepository.insertComponent(
                        repoId = component.repoId,
                        filePath = component.filePath,
                        role = component.role.name,
                        localPath = progress.localPath!!,
                        sizeBytes = component.sizeHint?.let { parseSizeHint(it) },
                    )
                    componentRepository.linkComponentToModel(modelId, compId, component.role.name)
                    _setupComponents.update { list ->
                        list.map {
                            if (it.repoId == component.repoId && it.filePath == component.filePath) {
                                it.copy(isDownloaded = true, progress = null)
                            } else it
                        }
                    }
                }
            }
            _installBytesCompleted += component.sizeHint?.let { parseSizeHint(it) } ?: 0L
        }
        return allMetadata.values.toList()
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
        _searchQuery.update { query }
    }

    fun performSearch() {
        if (_browseMode.value != ModelHubBrowseMode.LanguageModels) return
        val query = _searchQuery.value
        if (query.isBlank()) {
            _searchError.update { "Please enter a search query" }
            return
        }
        val owner = beginModelRequest()
        searchRequestJob = viewModelScope.launch {
            _isSearchLoading.update { true }
            _searchError.update { null }
            when (val result = api.searchModels(SearchModelsParams(query = query))) {
                is Result.Success -> {
                    if (!ownsSearchRequest(owner, query)) return@launch
                    _searchResponse.update { result.data }
                    _searchError.update { null }
                    startRecommendations(
                        result.data.models.orEmpty().filterNotNull().map { model ->
                            ListModelsResponse.Model(
                                id = model.id,
                                `private` = model.`private`,
                            )
                        },
                        defaultRecommendationWorkload(ModelHubBrowseMode.LanguageModels),
                        source = "search",
                    )
                }
                is Result.Error -> {
                    if (!ownsSearchRequest(owner, query)) return@launch
                    _searchError.update {
                        when (result.error) {
                            DataError.Network.NoInternet ->
                                "No internet connection. Please check your network and try again."
                            DataError.Network.Serialization ->
                                "Failed to process server response. The data format may be invalid."
                            DataError.Network.Unauthorized ->
                                "Authentication failed. Please check your credentials."
                            DataError.Network.RequestTimeout ->
                                "Request timed out. The server took too long to respond."
                            DataError.Network.Conflict ->
                                "Request conflict. Please refresh and try again."
                            DataError.Network.PayloadTooLarge ->
                                "Request too large. Try a shorter query."
                            DataError.Network.ServerError ->
                                "Server error occurred. Please try again later."
                            DataError.Network.Unknown ->
                                "An unexpected error occurred. Please try again."
                        }
                    }
                }
            }
            if (ownsModelRequest(owner)) _isSearchLoading.update { false }
        }
    }

    fun clearSearch() {
        invalidateModelRequests()
        _searchQuery.update { "" }
        _searchResponse.update { null }
        _searchError.update { null }
        _listResponse.value?.models.orEmpty().filterNotNull().let { models ->
            startRecommendations(models, defaultRecommendationWorkload(_browseMode.value))
        }
    }

    fun setRecommendationOrdering(ordering: RecommendationOrdering) {
        _recommendationOrdering.value = ordering
        recommendationSession?.let { session ->
            viewModelScope.launch { recommendationService.setOrdering(session, ordering) }
        }
    }

    fun setModelOrdering(ordering: ModelOrdering) {
        _modelOrdering.value = ordering
        when (ordering) {
            ModelOrdering.Personalized -> setRecommendationOrdering(RecommendationOrdering.PERSONALIZED)
            is ModelOrdering.Server -> {
                updateParams(sort = ordering.value)
                setRecommendationOrdering(RecommendationOrdering.SERVER)
            }
        }
    }

    fun loadMoreRecommendations() {
        recommendationSession?.let { session ->
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
        recommendationJob?.cancel()
        recommendationSession?.cancel()
        recommendationJob = null
        recommendationSession = null
        _recommendedModels.value = emptyList()
    }

    private fun beginModelRequest(): Any {
        val owner = Any()
        activeModelRequestOwner = owner
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
        activeModelRequestOwner = Any()
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
        recommendationSession?.cancel()
        listRequestJob = null
        searchRequestJob = null
        recommendationJob = null
        recommendationSession = null
    }

    fun clearSearchError() {
        _searchError.update { null }
    }

    private suspend fun loadSetupComponentsForModel(modelId: String) {
        val setup = getModelSetup(modelId) ?: return
        val metadata = LinkedHashMap<SdCppComponent, DownloadMetadataDTO>()
        for (component in setup.components.filter { it.required }) {
            runCatching { createMetadataForComponent(component) }
                .getOrNull()
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

        _ggufFiles.update { files ->
            files.map { file ->
                val destination = file.artifact?.relativePath?.let(::normalizedDiffusersRelativePath)
                file.copy(
                    isDownloaded = file.artifact != null && individuallyInstalled.any { entry ->
                        entry.identity == file.artifact && entry.localRelativePath == destination
                    },
                    progress = null,
                )
            }
        }
        _setupComponents.update { components ->
            components.map { componentState ->
                val metadata = exactSetupComponentMetadata.entries
                    .singleOrNull { (component) ->
                        component.repoId == componentState.repoId && component.filePath == componentState.filePath
                    }
                    ?.value
                componentState.copy(
                    isDownloaded = metadata != null && individuallyInstalled.any { entry ->
                        entry.identity == metadata.artifact &&
                            entry.logicalRole == metadata.logicalRole &&
                            entry.localRelativePath == metadata.destinationRelativePath
                    },
                    progress = null,
                )
            }
        }

        if (!metadataComplete) return
        val aggregate = downloadManager.validatedBundle(modelId) ?: return
        val installed = _ggufFiles.value.asSequence()
            .mapNotNull { it.path.takeIf(String::isNotBlank) }
            .mapNotNull { triggered ->
                createExactBundleMetadata(triggered, exactSetupComponentMetadata)
                    ?.takeIf(aggregate::matchesExactBundle)
                    ?.let { triggered to it }
            }
            .firstOrNull() ?: return
        val installedIdentities = installed.second.map { it.artifact }.toSet()
        _ggufFiles.update { files ->
            files.map { it.copy(isDownloaded = it.artifact in installedIdentities, progress = null) }
        }
        _setupComponents.update { components ->
            components.map { state ->
                val identity = exactSetupComponentMetadata.entries.singleOrNull { (component) ->
                    component.repoId == state.repoId && component.filePath == state.filePath
                }?.value?.artifact
                state.copy(isDownloaded = identity in installedIdentities, progress = null)
            }
        }
        _selectedVariantPath.value = installed.first
        _validatedBundleReady.value = true
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
        val primaryIdentities = selected.map { it.artifact ?: return null }
        val identities = primaryIdentities + componentMetadata.values.map { it.artifact }
        val bundleId = artifactBundleId(identities) ?: return null
        val detail = _modelDetail.value
        val ordered = listOf(selected.first { it.path == triggeredPath }) + selected.filter { it.path != triggeredPath }
        val primaryMetadata = ordered.mapIndexed { index, file ->
            DownloadMetadataDTO(
                artifact = requireNotNull(file.artifact),
                logicalRole = if (index == 0) "model" else "component-${file.path.hashCode().toUInt()}",
                sizeBytes = file.artifact.expectedBytes,
                author = detail?.author,
                libraryName = detail?.libraryName,
                pipelineTag = detail?.pipelineTag,
                contextLength = null,
                destinationRelativePath = normalizedDiffusersRelativePath(file.path),
                bundleId = bundleId,
            )
        }
        return primaryMetadata + componentMetadata.values.map { it.copy(bundleId = bundleId) }
    }

    /** Legacy: download only components (called explicitly from settings/fix flow). */
    fun downloadSetupComponents(modelId: String) {
        startSetupComponentDownload(modelId, downloadForLaterConfirmed = false)
    }

    private fun startSetupComponentDownload(
        modelId: String,
        downloadForLaterConfirmed: Boolean,
    ) {
        if (_isDownloading.value) return
        _isDownloading.value = true
        viewModelScope.launch {
            _downloadError.update { null }
            try {
                val setup = getModelSetup(modelId) ?: throw ArtifactVerificationException()
                val allComponents = setup.components.filter { it.required }
                val componentMetadata = mutableMapOf<SdCppComponent, DownloadMetadataDTO>()
                for (component in allComponents) {
                    componentMetadata[component] = exactSetupComponentMetadata[component]
                        ?: createMetadataForComponent(component)
                }
                val selectedPath = _selectedVariantPath.value ?: _ggufFiles.value.firstOrNull()?.path
                    ?: throw ArtifactVerificationException()
                val ownedArtifacts = createExactBundleMetadata(selectedPath, componentMetadata)
                    ?: throw ArtifactVerificationException()
                val selectedPaths = selectDiffusionFilesToDownload(
                    _ggufFiles.value.filter { it.path.isNotBlank() },
                    selectedPath,
                ).mapTo(mutableSetOf()) { it.path }
                val primaryMetadata = ownedArtifacts.filter {
                    it.artifact.repositoryId == modelId && it.artifact.relativePath in selectedPaths
                }
                val ownedComponents = componentMetadata.mapValues { (_, metadata) ->
                    ownedArtifacts.single { it.artifact == metadata.artifact }
                }
                downloadDiffusionBundle(
                    modelId = modelId,
                    triggeredPath = selectedPath,
                    ownedMetadata = primaryMetadata,
                    downloadForLaterConfirmed = downloadForLaterConfirmed,
                )
                downloadMissingComponents(
                    modelId,
                    downloadForLaterConfirmed = downloadForLaterConfirmed,
                    preparedMetadata = ownedComponents,
                )
                if (!downloadManager.publishBundle(modelId, ownedArtifacts) ||
                    !downloadManager.validateBundle(modelId, ownedArtifacts)
                ) throw ArtifactVerificationException()
                persistDiffusionModelRecord(modelId, primaryMetadata, modelTypeForCurrentBrowseMode())
                refreshVerifiedDiffusionInstallation(modelId)
            } catch (error: DownloadAdmissionRejected) {
                if (error.admission is DownloadAdmission.ConfirmationRequired && !downloadForLaterConfirmed) {
                    requestDownloadForLaterConfirmation(PendingDownloadForLater.Repair(modelId))
                } else {
                    _downloadError.value = downloadAdmissionErrorMessage(error.admission)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: InsufficientStorageException) {
                val required = formatBytes(e.requiredBytes)
                val available = formatBytes(e.availableBytes)
                _downloadError.update {
                    "Not enough storage space. Need $required but only $available is available."
                }
                _setupComponents.update { list -> list.map { it.copy(progress = null) } }
            } catch (_: Exception) {
                _downloadError.update { "Download failed. Please check your connection and try again." }
                _setupComponents.update { list -> list.map { it.copy(progress = null) } }
            } finally {
                _isDownloading.update { false }
            }
        }
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
            destinationRelativePath = normalizedDiffusersRelativePath(identity.relativePath),
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
