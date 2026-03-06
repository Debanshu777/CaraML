package com.debanshu777.caraml.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.debanshu777.caraml.storage.localModel.LocalModelEntity
import com.debanshu777.caraml.storage.localModel.LocalModelRepository
import com.debanshu777.huggingfacemanager.download.StoragePathProvider
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class DownloadedModelsViewModel(
    private val localModelRepository: LocalModelRepository,
    private val storagePathProvider: StoragePathProvider
) : ViewModel() {

    val downloadedModels: StateFlow<List<LocalModelEntity>> =
        localModelRepository.getAllDownloadedFiles()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

    /**
     * Resolves the model file path. Prefers stored localPath when the file exists there
     * (e.g. right after download). Falls back to recomputed path when localPath is
     * invalid (e.g. after reinstall or simulator reset).
     */
    fun getResolvedModelPath(model: LocalModelEntity): String {
        if (model.localPath.isNotBlank() && storagePathProvider.fileExists(model.localPath)) {
            return model.localPath
        }
        val dir = storagePathProvider.getModelsStorageDirectory(model.modelId)
        return "$dir/${model.filename}"
    }
}
