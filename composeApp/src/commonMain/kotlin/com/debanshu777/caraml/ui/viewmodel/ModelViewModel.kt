package com.debanshu777.caraml.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.debanshu777.caraml.storage.localModel.LocalModelRepository
import com.debanshu777.huggingfacemanager.HuggingFaceApi
import com.debanshu777.huggingfacemanager.api.ListModelsParams
import com.debanshu777.huggingfacemanager.api.SearchModelsParams
import com.debanshu777.huggingfacemanager.api.error.DataError
import com.debanshu777.huggingfacemanager.api.error.Result
import com.debanshu777.huggingfacemanager.download.DownloadManager
import com.debanshu777.huggingfacemanager.download.DownloadMetadataDTO
import com.debanshu777.huggingfacemanager.download.InsufficientStorageException
import com.debanshu777.huggingfacemanager.model.ModelDetailResponse
import com.debanshu777.huggingfacemanager.model.ModelSort
import com.debanshu777.huggingfacemanager.model.ParameterRange
import com.debanshu777.huggingfacemanager.model.ListModelsResponse
import com.debanshu777.huggingfacemanager.model.SearchModelsResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.round

data class GgufFileUiState(
    val path: String,
    val filename: String,
    val sizeBytes: Long?,
    val isDownloaded: Boolean,
    val progress: Float?  // null = not downloading, 0-100 = in progress
)

class ModelViewModel(
    private val api: HuggingFaceApi,
    private val localModelRepository: LocalModelRepository,
    private val downloadManager: DownloadManager
) : ViewModel() {

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

    fun loadModels() {
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

    fun loadDetail(modelId: String) {
        if (modelId.isBlank()) return
        viewModelScope.launch {
            _isDetailLoading.update { true }
            _detailError.update { null }
            _modelDetail.update { null }
            _ggufFiles.update { emptyList() }

            when (val detailResult = api.getModelDetail(modelId)) {
                is Result.Success -> {
                    _modelDetail.update { detailResult.data }
                    _detailError.update { null }
                    
                    // Load GGUF files
                    when (val treeResult = api.getModelFileTree(modelId)) {
                        is Result.Success -> {
                            val downloaded = localModelRepository.getDownloadedFilenames(modelId)
                            _ggufFiles.update {
                                treeResult.data.map { item ->
                                    val fn = item.path?.substringAfterLast('/') ?: item.path ?: ""
                                    GgufFileUiState(
                                        path = item.path ?: "",
                                        filename = fn,
                                        sizeBytes = item.size,
                                        isDownloaded = fn in downloaded,
                                        progress = null
                                    )
                                }
                            }
                        }
                        is Result.Error -> {
                            // Optionally handle GGUF file tree error
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
            try {
                downloadManager.download(modelId, path, metadata).collect { progress ->
                    _ggufFiles.update { list ->
                        list.map {
                            if (it.path == path) it.copy(progress = progress.percentage) else it
                        }
                    }
                    if (progress.localPath != null) {
                        localModelRepository.insert(
                            modelId = modelId,
                            filename = path.substringAfterLast('/').ifEmpty { path },
                            localPath = progress.localPath!!,
                            sizeBytes = metadata.sizeBytes,
                            author = metadata.author,
                            libraryName = metadata.libraryName,
                            pipelineTag = metadata.pipelineTag
                        )
                    }
                }
                // Refresh download status
                val downloaded = localModelRepository.getDownloadedFilenames(modelId)
                _ggufFiles.update { list ->
                    list.map { file ->
                        if (file.path == path) {
                            file.copy(isDownloaded = true, progress = null)
                        } else {
                            file.copy(isDownloaded = file.filename in downloaded)
                        }
                    }
                }
            } catch (e: InsufficientStorageException) {
                val required = formatBytes(e.requiredBytes)
                val available = formatBytes(e.availableBytes)
                _downloadError.update {
                    "Not enough storage space. Need $required but only $available is available."
                }
                _ggufFiles.update { list ->
                    list.map { file ->
                        if (file.path == path) file.copy(progress = null) else file
                    }
                }
            } catch (_: Exception) {
                _downloadError.update { "Download failed. Please check your connection and try again." }
                _ggufFiles.update { list ->
                    list.map { file ->
                        if (file.path == path) file.copy(progress = null) else file
                    }
                }
            } finally {
                _isDownloading.update { false }
            }
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
        _searchQuery.update { query }
    }

    fun performSearch() {
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
