package com.debanshu777.caraml.features.modelhub.presentation.downloaded

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.debanshu777.caraml.core.domain.ModelReadinessReconciler
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.core.storage.localmodel.LocalModelRepository
import com.debanshu777.huggingfacemanager.download.StoragePathProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ReadinessFilter { ALL, READY, PARTIAL }

class DownloadedModelsViewModel(
    private val localModelRepository: LocalModelRepository,
    private val storagePathProvider: StoragePathProvider,
) : ViewModel() {

    private val reconciler = ModelReadinessReconciler(localModelRepository, storagePathProvider)

    private val _allDownloadedModels = localModelRepository.getAllDownloadedFiles()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _readinessFilter = MutableStateFlow(ReadinessFilter.ALL)
    val readinessFilter: StateFlow<ReadinessFilter> = _readinessFilter.asStateFlow()

    /** Filtered list of downloaded models based on the active readiness filter. */
    val downloadedModels: StateFlow<List<LocalModelEntity>> = combine(
        _allDownloadedModels,
        _readinessFilter,
    ) { all, filter ->
        when (filter) {
            ReadinessFilter.ALL -> all
            ReadinessFilter.READY -> all.filter { it.componentStatus == LocalModelEntity.STATUS_READY || it.componentStatus == null }
            ReadinessFilter.PARTIAL -> all.filter { it.componentStatus == LocalModelEntity.STATUS_PARTIAL }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    private val _selectionMode = MutableStateFlow(false)
    val selectionMode: StateFlow<Boolean> = _selectionMode.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

    private val _isDeleting = MutableStateFlow(false)
    val isDeleting: StateFlow<Boolean> = _isDeleting.asStateFlow()

    private val _deleteResultMessage = MutableStateFlow<String?>(null)
    val deleteResultMessage: StateFlow<String?> = _deleteResultMessage.asStateFlow()

    init {
        // Reconcile readiness state in the background when the ViewModel starts
        viewModelScope.launch(Dispatchers.IO) {
            reconciler.reconcile()
        }
    }

    fun setReadinessFilter(filter: ReadinessFilter) {
        _readinessFilter.update { filter }
    }

    fun beginSelection(model: LocalModelEntity) {
        _selectionMode.value = true
        _selectedIds.update { it + model.id }
    }

    fun toggleSelection(model: LocalModelEntity) {
        if (!_selectionMode.value) return
        _selectedIds.update { cur ->
            if (model.id in cur) cur - model.id else cur + model.id
        }
    }

    fun clearSelection() {
        _selectionMode.value = false
        _selectedIds.value = emptySet()
    }

    fun acknowledgeDeleteResult() {
        _deleteResultMessage.value = null
    }

    fun deleteSelected() {
        if (_isDeleting.value) return
        val ids = _selectedIds.value
        if (ids.isEmpty()) return
        val snapshot = _allDownloadedModels.value.filter { it.id in ids }
        viewModelScope.launch {
            _isDeleting.value = true
            _deleteResultMessage.value = null
            var anyFailed = false
            val removedIds = mutableSetOf<Long>()
            withContext(Dispatchers.IO) {
                for (entity in snapshot) {
                    val ok = storagePathProvider.deleteDownloadedModelContent(entity.modelId, entity.localPath)
                    if (!ok) {
                        anyFailed = true
                        continue
                    }
                    localModelRepository.deleteByModelIdAndFilename(entity.modelId, entity.filename)
                    removedIds.add(entity.id)
                }
            }
            _selectedIds.update { it - removedIds }
            if (_selectedIds.value.isEmpty()) {
                _selectionMode.value = false
            }
            if (anyFailed) {
                _deleteResultMessage.value = "Could not remove some items. Try again."
            }
            _isDeleting.value = false
        }
    }

    suspend fun trackModelUsage(model: LocalModelEntity) {
        localModelRepository.incrementUsageCount(model.modelId, model.filename)
    }
}
