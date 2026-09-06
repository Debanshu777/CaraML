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
import com.debanshu777.huggingfacemanager.download.IncompleteDownloadException
import com.debanshu777.huggingfacemanager.download.InsufficientStorageException
import com.debanshu777.huggingfacemanager.download.StoragePathProvider
import com.debanshu777.huggingfacemanager.model.DIFFUSERS_BUNDLE_DB_FILENAME
import com.debanshu777.huggingfacemanager.model.ModelDetailResponse
import com.debanshu777.huggingfacemanager.model.ModelFileWeightFilter
import com.debanshu777.huggingfacemanager.model.isDiffusersModelDirectory
import com.debanshu777.huggingfacemanager.model.normalizeDiffusersFileNames
import com.debanshu777.huggingfacemanager.model.ModelSort
import com.debanshu777.huggingfacemanager.model.ParameterRange
import com.debanshu777.huggingfacemanager.model.ListModelsResponse
import com.debanshu777.huggingfacemanager.model.SearchModelsResponse
import com.debanshu777.huggingfacemanager.sdcpp.loadSdCppCuratedCatalog
import com.debanshu777.huggingfacemanager.sdcpp.toListModelsResponse
import com.debanshu777.huggingfacemanager.sdcpp.toVideoListModelsResponse
import com.debanshu777.huggingfacemanager.sdcpp.SdCppComponentChecker
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

    private val componentChecker = SdCppComponentChecker(storagePathProvider)
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
            _selectedVariantPath.update { null }

            when (val detailResult = api.getModelDetail(modelId)) {
                is Result.Success -> {
                    _modelDetail.update { detailResult.data }
                    _detailError.update { null }

                    // Load setup components for diffusion models
                    if (hubBrowseMode == ModelHubBrowseMode.DiffusionImage ||
                        hubBrowseMode == ModelHubBrowseMode.DiffusionVideo) {
                        loadSetupComponentsForModel(modelId)
                    }

                    val weightFilter = when (hubBrowseMode) {
                        ModelHubBrowseMode.LanguageModels -> ModelFileWeightFilter.GgufOnly
                        ModelHubBrowseMode.DiffusionImage,
                        ModelHubBrowseMode.DiffusionVideo ->
                            ModelFileWeightFilter.StableDiffusionCppWeights
                    }
                    when (val treeResult = api.getModelFileTree(modelId, weightFilter)) {
                        is Result.Success -> {
                            val downloaded = localModelRepository.getDownloadedFilenames(modelId)
                            val bundleDownloaded =
                                DIFFUSERS_BUNDLE_DB_FILENAME in downloaded &&
                                    hubBrowseMode != ModelHubBrowseMode.LanguageModels
                            _ggufFiles.update {
                                treeResult.data.map { item ->
                                    val rel = item.path ?: ""
                                    val fn = rel.substringAfterLast('/').ifEmpty { rel }
                                    val isDownloaded = when (hubBrowseMode) {
                                        ModelHubBrowseMode.DiffusionImage,
                                        ModelHubBrowseMode.DiffusionVideo,
                                        -> bundleDownloaded || rel in downloaded || fn in downloaded
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
                                val firstPending = _ggufFiles.value.firstOrNull { !it.isDownloaded }
                                _selectedVariantPath.update { firstPending?.path }
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
                    ?: _ggufFiles.value.firstOrNull { !it.isDownloaded }?.path
                    ?: return@launch

                // Compute total bytes so the progress bar has a denominator
                val allPending = _ggufFiles.value.filter { !it.isDownloaded && it.path.isNotBlank() }
                val selectedFiles = selectDiffusionFilesToDownload(allPending, variantPath)
                val setup = getModelSetup(modelId)
                val missingComps = if (setup != null && !setup.selfContained)
                    componentChecker.getMissingComponents(setup) else emptyList()
                _installBytesTotal = selectedFiles.sumOf { it.sizeBytes ?: 0L } +
                    missingComps.sumOf { it.sizeHint?.let { h -> parseSizeHint(h) } ?: 0L }
                _installBytesCompleted = 0L
                _installProgress.update { InstallProgress(bytesTotal = _installBytesTotal) }

                val selectedArtifact = _ggufFiles.value.find { it.path == variantPath }?.artifact
                    ?: throw IllegalStateException("Exact artifact identity is unavailable")
                val sharedMeta = DownloadMetadataDTO(
                    artifact = selectedArtifact,
                    logicalRole = "model",
                    sizeBytes = selectedArtifact.expectedBytes,
                    author = _modelDetail.value?.author,
                    libraryName = _modelDetail.value?.libraryName,
                    pipelineTag = _modelDetail.value?.pipelineTag,
                    contextLength = null,
                )

                when (refreshDownloadAdmission(modelId, sharedMeta)) {
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
                        _downloadError.value = "This download is blocked because current device requirements are not met."
                        return@launch
                    }
                }

                downloadDiffusionBundle(
                    modelId = modelId,
                    triggeredPath = variantPath,
                    sharedMetadata = sharedMeta,
                    modelType = modelType,
                    initialComponentStatus = LocalModelEntity.STATUS_PARTIAL,
                    downloadForLaterConfirmed = downloadForLaterConfirmed,
                )
                downloadMissingComponents(modelId, downloadForLaterConfirmed)
                refreshGgufFilesDownloadState(modelId, isDiffusion = true)
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
                when (refreshDownloadAdmission(modelId, metadata)) {
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
                        _downloadError.value = "This download is blocked because current device requirements are not met."
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
        val downloaded = localModelRepository.getDownloadedFilenames(modelId)
        val bundleDownloaded = DIFFUSERS_BUNDLE_DB_FILENAME in downloaded && isDiffusion
        _ggufFiles.update { list ->
            list.map { file ->
                val marked = when {
                    bundleDownloaded -> true
                    else -> file.filename in downloaded || file.path in downloaded
                }
                file.copy(isDownloaded = marked, progress = null)
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
            if (progress.localPath != null) {
                val relativePath = path.trim().replace('\\', '/').trimStart('/')
                val filename = relativePath.substringAfterLast('/').ifEmpty { relativePath }
                localModelRepository.insert(
                    modelId = modelId,
                    filename = filename,
                    localPath = progress.localPath!!,
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
        sharedMetadata: DownloadMetadataDTO,
        modelType: String,
        initialComponentStatus: String? = null,
        downloadForLaterConfirmed: Boolean = false,
    ) {
        val allPending = _ggufFiles.value.filter { !it.isDownloaded && it.path.isNotBlank() }
        if (allPending.isEmpty()) return

        val selected = selectDiffusionFilesToDownload(allPending, triggeredPath)
        if (selected.isEmpty()) return

        val totalRequired = selected.sumOf { it.sizeBytes ?: 0L }
        if (totalRequired > 0L) {
            val available = storagePathProvider.getAvailableStorageBytes()
            if (available < totalRequired) {
                throw InsufficientStorageException(totalRequired, available)
            }
        }

        val detail = _modelDetail.value
        fun metaFor(file: GgufFileUiState): DownloadMetadataDTO = DownloadMetadataDTO(
            artifact = file.artifact ?: throw IllegalStateException("Exact artifact identity is unavailable"),
            logicalRole = if (file.path == triggeredPath) "model" else "component-${file.path.hashCode().toUInt()}",
            sizeBytes = file.artifact.expectedBytes,
            author = detail?.author ?: sharedMetadata.author,
            libraryName = detail?.libraryName ?: sharedMetadata.libraryName,
            pipelineTag = detail?.pipelineTag ?: sharedMetadata.pipelineTag,
            contextLength = sharedMetadata.contextLength,
        )

        val ordered = if (selected.any { it.path == triggeredPath }) {
            listOf(selected.first { it.path == triggeredPath }) +
                selected.filter { it.path != triggeredPath }
        } else {
            selected
        }

        for (file in ordered) {
            val meta = metaFor(file)
            if (!isDownloadAdmitted(modelId, meta, downloadForLaterConfirmed)) {
                throw IllegalStateException()
            }
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

        val modelRoot = storagePathProvider.getModelsStorageDirectory(modelId)
        normalizeDiffusersFileNames(
            rootDir = modelRoot,
            fileExists = storagePathProvider::fileExists,
            renameFile = storagePathProvider::renameFile,
        )
        val diffusersOk = isDiffusersModelDirectory(modelRoot, storagePathProvider::fileExists)

        if (diffusersOk) {
            localModelRepository.deleteAllForModelId(modelId)
            val totalSize = selected.sumOf { it.sizeBytes ?: 0L }.takeIf { it > 0L }
            localModelRepository.insert(
                modelId = modelId,
                filename = DIFFUSERS_BUNDLE_DB_FILENAME,
                localPath = modelRoot,
                sizeBytes = totalSize,
                author = detail?.author,
                libraryName = detail?.libraryName,
                pipelineTag = detail?.pipelineTag,
                contextLength = null,
                modelType = modelType,
                componentStatus = initialComponentStatus,
                isMainModel = true,
            )
        } else {
            val fallback = ordered.lastOrNull() ?: return
            val relativePath = fallback.path.trim().replace('\\', '/').trimStart('/')
            val localPath = "$modelRoot/$relativePath"
            if (!storagePathProvider.fileExists(localPath)) {
                throw IllegalStateException()
            }
            localModelRepository.deleteAllForModelId(modelId)
            localModelRepository.insert(
                modelId = modelId,
                filename = relativePath.substringAfterLast('/').ifEmpty { relativePath },
                localPath = localPath,
                sizeBytes = fallback.sizeBytes,
                author = detail?.author,
                libraryName = detail?.libraryName,
                pipelineTag = detail?.pipelineTag,
                contextLength = null,
                modelType = modelType,
                componentStatus = initialComponentStatus,
                isMainModel = true,
            )
        }
    }

    /**
     * Downloads all missing required components for the given model.
     * Already-on-disk components are skipped. Records each download in ComponentRepository
     * and marks the model as "ready" when done.
     */
    private suspend fun downloadMissingComponents(
        modelId: String,
        downloadForLaterConfirmed: Boolean = false,
    ) {
        val setup = getModelSetup(modelId)
        if (setup == null || setup.selfContained) {
            localModelRepository.updateComponentStatus(modelId, LocalModelEntity.STATUS_READY)
            return
        }

        val missingComponents = componentChecker.getMissingComponents(setup)
        if (missingComponents.isEmpty()) {
            localModelRepository.updateComponentStatus(modelId, LocalModelEntity.STATUS_READY)
            return
        }

        for (component in missingComponents) {
            val meta = createMetadataForComponent(component)
            if (!isDownloadAdmitted(modelId, meta, downloadForLaterConfirmed)) {
                throw IllegalStateException()
            }
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

        localModelRepository.updateComponentStatus(modelId, LocalModelEntity.STATUS_READY)
    }

    private suspend fun isDownloadAdmitted(
        modelId: String,
        metadata: DownloadMetadataDTO,
        downloadForLaterConfirmed: Boolean,
    ): Boolean = when (refreshDownloadAdmission(modelId, metadata)) {
        DownloadAdmission.Allowed -> true
        is DownloadAdmission.ConfirmationRequired -> downloadForLaterConfirmed
        is DownloadAdmission.Blocked -> false
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
        val componentsStatus = componentChecker.getComponentsStatus(setup)
        val linkedComponents = componentRepository.getComponentsForModel(modelId)

        _setupComponents.update {
            componentsStatus.map { (component, isDownloaded) ->
                // sharedFrom: component is on disk but was NOT downloaded specifically for this model
                val sharedFrom = if (isDownloaded) {
                    val linkedToThis = linkedComponents.any {
                        it.repoId == component.repoId && it.filePath == component.filePath
                    }
                    if (!linkedToThis) "another model" else null
                } else null
                SetupComponentUiState(
                    role = component.role,
                    repoId = component.repoId,
                    filePath = component.filePath,
                    sizeHint = component.sizeHint,
                    isDownloaded = isDownloaded,
                    progress = null,
                    required = component.required,
                    sharedFrom = sharedFrom,
                )
            }
        }
    }

    /** Legacy: download only components (called explicitly from settings/fix flow). */
    fun downloadSetupComponents(modelId: String) {
        if (_isDownloading.value) return
        _isDownloading.value = true
        viewModelScope.launch {
            _downloadError.update { null }
            try {
                downloadMissingComponents(modelId)
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
            contextLength = null
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
