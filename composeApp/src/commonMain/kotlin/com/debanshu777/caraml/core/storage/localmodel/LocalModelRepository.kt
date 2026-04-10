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
    ) {
        dao.insert(
            LocalModelEntity(
                modelId = modelId,
                filename = filename,
                localPath = localPath,
                sizeBytes = sizeBytes,
                downloadedAt = 0L,
                author = author,
                libraryName = libraryName,
                pipelineTag = pipelineTag,
                contextLength = contextLength,
                modelType = modelType,
            )
        )
    }
}
