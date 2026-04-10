package com.debanshu777.caraml.features.modelhub.presentation.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.debanshu777.caraml.core.storage.localmodel.LocalModelRepository
import com.debanshu777.caraml.core.storage.localmodel.ModelType
import com.debanshu777.huggingfacemanager.HuggingFaceApi
import com.debanshu777.huggingfacemanager.api.ListModelsParams
import com.debanshu777.huggingfacemanager.api.SearchModelsParams
import com.debanshu777.huggingfacemanager.api.error.DataError
import com.debanshu777.huggingfacemanager.api.error.Result
import com.debanshu777.huggingfacemanager.download.DownloadManager
import com.debanshu777.huggingfacemanager.download.DownloadMetadataDTO
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.round

data class GgufFileUiState(
    val path: String,
    val filename: String,
    val sizeBytes: Long?,
    val isDownloaded: Boolean,
    val progress: Float?
)

data class StorageInfoUiState(
    val totalDeviceBytes: Long = 0L,
    val availableDeviceBytes: Long = 0L,
    val usedByModelsBytes: Long = 0L
)

class ModelViewModel(
    private val api: HuggingFaceApi,
    private val localModelRepository: LocalModelRepository,
    private val downloadManager: DownloadManager,
    private val storagePathProvider: StoragePathProvider
) : ViewModel() {

    val storageInfo: StateFlow<StorageInfoUiState> =
        localModelRepository.getTotalDownloadedSizeBytes()
            .map { usedBytes ->
                StorageInfoUiState(
                    totalDeviceBytes = storagePathProvider.getTotalStorageBytes(),
                    availableDeviceBytes = storagePathProvider.getAvailableStorageBytes(),
                    usedByModelsBytes = usedBytes
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

    private fun modelTypeForCurrentBrowseMode(): String =
        when (_browseMode.value) {
            ModelHubBrowseMode.LanguageModels -> ModelType.TEXT
            ModelHubBrowseMode.DiffusionImage -> ModelType.IMAGE
            ModelHubBrowseMode.DiffusionVideo -> ModelType.VIDEO
        }

    fun setBrowseMode(mode: ModelHubBrowseMode) {
        val previous = _browseMode.value
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
                _listResponse.update { sdCppCatalog.image.toListModelsResponse() }
            }

            ModelHubBrowseMode.DiffusionVideo -> {
                resetSearchStateForCuratedHub()
                _listError.update { null }
                _isListLoading.update { false }
                _listResponse.update { sdCppCatalog.video.toVideoListModelsResponse() }
            }
        }
    }

    fun loadModels() {
        if (_browseMode.value != ModelHubBrowseMode.LanguageModels) return
        viewModelScope.launch {
            _isListLoading.update { true }
            _listError.update { null }
            when (val result = api.listModels(_listParams.value)) {
                is Result.Success -> {
                    _listResponse.update { result.data }
                    _listError.update { null }
                }
                is Result.Error -> {
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
            _isListLoading.update { false }
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
                                    GgufFileUiState(
                                        path = rel,
                                        filename = fn,
                                        sizeBytes = item.size,
                                        isDownloaded = isDownloaded,
                                        progress = null
                                    )
                                }
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

    fun startDownload(modelId: String, path: String, metadata: DownloadMetadataDTO) {
        if (_isDownloading.value) return
        viewModelScope.launch {
            _isDownloading.update { true }
            _downloadError.update { null }
            val modelType = modelTypeForCurrentBrowseMode()
            val diffusionHub = when (_browseMode.value) {
                ModelHubBrowseMode.DiffusionImage,
                ModelHubBrowseMode.DiffusionVideo,
                -> true
                ModelHubBrowseMode.LanguageModels -> false
            }
            try {
                if (diffusionHub) {
                    downloadDiffusionBundle(
                        modelId,
                        triggeredPath = path,
                        sharedMetadata = metadata,
                        modelType = modelType,
                    )
                } else {
                    downloadSingleWeight(modelId, path, metadata, modelType = modelType)
                }
                val downloaded = localModelRepository.getDownloadedFilenames(modelId)
                val bundleDownloaded =
                    DIFFUSERS_BUNDLE_DB_FILENAME in downloaded && diffusionHub
                _ggufFiles.update { list ->
                    list.map { file ->
                        val rel = file.path
                        val marked = when {
                            bundleDownloaded && diffusionHub -> true
                            else -> file.filename in downloaded || rel in downloaded
                        }
                        file.copy(isDownloaded = marked, progress = null)
                    }
                }
            } catch (e: InsufficientStorageException) {
                val required = formatBytes(e.requiredBytes)
                val available = formatBytes(e.availableBytes)
                _downloadError.update {
                    "Not enough storage space. Need $required but only $available is available."
                }
                _ggufFiles.update { list -> list.map { it.copy(progress = null) } }
            } catch (_: Exception) {
                _downloadError.update { "Download failed. Please check your connection and try again." }
                _ggufFiles.update { list -> list.map { it.copy(progress = null) } }
            } finally {
                _isDownloading.update { false }
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
                localModelRepository.insert(
                    modelId = modelId,
                    filename = relativePath.substringAfterLast('/').ifEmpty { relativePath },
                    localPath = progress.localPath!!,
                    sizeBytes = metadata.sizeBytes,
                    author = metadata.author,
                    libraryName = metadata.libraryName,
                    pipelineTag = metadata.pipelineTag,
                    contextLength = metadata.contextLength,
                    modelType = modelType,
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
            sizeBytes = file.sizeBytes,
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
            downloadManager.download(modelId, file.path, meta).collect { progress ->
                _ggufFiles.update { list ->
                    list.map {
                        if (it.path == file.path) it.copy(progress = progress.percentage) else it
                    }
                }
            }
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
            )
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
        _searchQuery.update { query }
    }

    fun performSearch() {
        if (_browseMode.value != ModelHubBrowseMode.LanguageModels) return
        val query = _searchQuery.value
        if (query.isBlank()) {
            _searchError.update { "Please enter a search query" }
            return
        }
        viewModelScope.launch {
            _isSearchLoading.update { true }
            _searchError.update { null }
            when (val result = api.searchModels(SearchModelsParams(query = query))) {
                is Result.Success -> {
                    _searchResponse.update { result.data }
                    _searchError.update { null }
                }
                is Result.Error -> {
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
            _isSearchLoading.update { false }
        }
    }

    fun clearSearch() {
        _searchQuery.update { "" }
        _searchResponse.update { null }
        _searchError.update { null }
    }

    private fun resetSearchStateForCuratedHub() {
        _searchQuery.update { "" }
        _searchResponse.update { null }
        _searchError.update { null }
        _isSearchLoading.update { false }
    }

    fun clearSearchError() {
        _searchError.update { null }
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
