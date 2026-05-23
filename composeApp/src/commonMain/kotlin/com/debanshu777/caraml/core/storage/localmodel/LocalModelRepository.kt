package com.debanshu777.caraml.core.storage.localmodel

import kotlinx.coroutines.flow.Flow

class LocalModelRepository(private val dao: LocalModelDao) {
    suspend fun getDownloadedFilenames(modelId: String): Set<String> =
        dao.getFilenamesByModelId(modelId).toSet()

    suspend fun deleteByModelIdAndFilename(modelId: String, filename: String) {
        dao.deleteByModelIdAndFilename(modelId, filename)
    }

    suspend fun deleteAllForModelId(modelId: String) {
        dao.deleteAllForModelId(modelId)
    }

    fun getAllDownloadedFiles(): Flow<List<LocalModelEntity>> =
        dao.getAllDownloadedFiles()

    fun getDownloadedFilesByType(modelType: String): Flow<List<LocalModelEntity>> =
        dao.getDownloadedFilesByType(modelType)

    fun getTotalDownloadedSizeBytes(): Flow<Long> =
        dao.getTotalDownloadedSizeBytes()

    suspend fun incrementUsageCount(modelId: String, filename: String) {
        dao.incrementUsageCount(modelId, filename)
    }

    suspend fun updateComponentStatus(modelId: String, status: String) {
        dao.updateComponentStatus(modelId, status)
    }

    fun getReadyMainModels() = dao.getReadyMainModels()

    fun getPartialMainModels() = dao.getPartialMainModels()

    fun getAllMainModels() = dao.getAllMainModels()

    suspend fun getMainModelByModelId(modelId: String) = dao.getMainModelByModelId(modelId)

    suspend fun getMainModels(): List<LocalModelEntity> = dao.getMainModels()

    suspend fun updateArch(modelId: String, arch: String) {
        dao.updateArch(modelId, arch)
    }

    suspend fun insert(
        modelId: String,
        filename: String,
        localPath: String,
        sizeBytes: Long?,
        author: String?,
        libraryName: String?,
        pipelineTag: String?,
        contextLength: Int? = null,
        modelType: String,
        componentStatus: String? = null,
        isMainModel: Boolean = true,
    ) {
        dao.insert(
            LocalModelEntity(
                modelId = modelId,
                filename = filename,
                localPath = localPath,
                sizeBytes = sizeBytes,
                downloadedAt = System.currentTimeMillis(),
                author = author,
                libraryName = libraryName,
                pipelineTag = pipelineTag,
                contextLength = contextLength,
                modelType = modelType,
                componentStatus = componentStatus,
                isMainModel = isMainModel,
            )
        )
    }
}
