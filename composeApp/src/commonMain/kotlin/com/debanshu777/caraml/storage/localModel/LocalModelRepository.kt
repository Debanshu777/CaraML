package com.debanshu777.caraml.storage.localModel

import kotlinx.coroutines.flow.Flow

class LocalModelRepository(private val dao: LocalModelDao) {
    suspend fun getDownloadedFilenames(modelId: String): Set<String> =
        dao.getFilenamesByModelId(modelId).toSet()

    fun getAllDownloadedFiles(): Flow<List<LocalModelEntity>> =
        dao.getAllDownloadedFiles()

    suspend fun insert(
        modelId: String,
        filename: String,
        localPath: String,
        sizeBytes: Long?,
        author: String?,
        libraryName: String?,
        pipelineTag: String?
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
                pipelineTag = pipelineTag
            )
        )
    }
}
