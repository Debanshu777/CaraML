package com.debanshu777.caraml.core.storage.localmodel

import kotlinx.coroutines.flow.Flow

class LocalModelRepository(private val dao: LocalModelDao) {
    fun getAllDownloadedFiles(): Flow<List<LocalModelEntity>> =
        dao.getAllDownloadedFiles()

    fun getTotalDownloadedSizeBytes(): Flow<Long> =
        dao.getTotalDownloadedSizeBytes()

    suspend fun incrementUsageCount(modelId: String, filename: String) {
        dao.incrementUsageCount(modelId, filename)
    }

    suspend fun updateComponentStatus(modelId: String, status: String) {
        dao.updateComponentStatus(modelId, status)
    }
    suspend fun getMainModels(): List<LocalModelEntity> = dao.getMainModels()

    suspend fun demoteMmprojFilesFromMain() {
        dao.demoteMmprojFilesFromMain()
    }
}
