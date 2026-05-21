package com.debanshu777.caraml.core.storage.component

import kotlinx.coroutines.flow.Flow

class ComponentRepository(private val dao: DownloadedComponentDao) {

    /**
     * Inserts a component into the downloaded_component table (IGNORE on conflict).
     * Returns the row id (or the existing id if already present).
     */
    suspend fun insertComponent(
        repoId: String,
        filePath: String,
        role: String,
        localPath: String,
        sizeBytes: Long?,
    ): Long {
        val existing = dao.getByRepoAndPath(repoId, filePath)
        if (existing != null) return existing.id
        return dao.insertComponent(
            DownloadedComponentEntity(
                repoId = repoId,
                filePath = filePath,
                role = role,
                localPath = localPath,
                sizeBytes = sizeBytes,
                downloadedAt = System.currentTimeMillis(),
            )
        ).let { inserted ->
            // IGNORE returned -1 — fetch the real id
            if (inserted == -1L) dao.getByRepoAndPath(repoId, filePath)?.id ?: inserted
            else inserted
        }
    }

    /** Links a downloaded component to a model (records which model needed it). */
    suspend fun linkComponentToModel(modelId: String, componentId: Long, role: String) {
        dao.insertLink(ModelComponentLinkEntity(modelId = modelId, componentId = componentId, role = role))
    }

    /** Returns true if a component with this (repoId, filePath) key already exists on disk. */
    suspend fun isComponentDownloaded(repoId: String, filePath: String): Boolean =
        dao.isComponentDownloaded(repoId, filePath)

    /** Returns all components linked to a given model. */
    suspend fun getComponentsForModel(modelId: String): List<DownloadedComponentEntity> =
        dao.getComponentsForModel(modelId)

    fun getAllComponents(): Flow<List<DownloadedComponentEntity>> =
        dao.getAllComponents()

    suspend fun deleteComponent(repoId: String, filePath: String) {
        dao.deleteByRepoAndPath(repoId, filePath)
    }
}
