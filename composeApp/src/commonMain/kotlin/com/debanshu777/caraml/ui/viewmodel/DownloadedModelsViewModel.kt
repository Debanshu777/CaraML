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
    private val localModelRepository: LocalModelRepository
) : ViewModel() {

    val downloadedModels: StateFlow<List<LocalModelEntity>> =
        localModelRepository.getAllDownloadedFiles()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

    suspend fun trackModelUsage(model: LocalModelEntity) {
        localModelRepository.incrementUsageCount(model.modelId, model.filename)
    }
}
