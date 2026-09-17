package com.debanshu777.caraml.core.download

import kotlinx.coroutines.flow.Flow

interface DownloadTaskStore {
    suspend fun create(request: DownloadBatchRequest, nowEpochMs: Long): String
    fun observeForModel(modelId: String): Flow<List<DownloadBatchSnapshot>>
    suspend fun getBatch(batchId: String): DownloadBatchSnapshot?
    suspend fun recoverableBatches(): List<DownloadBatchSnapshot>
    suspend fun claim(artifactId: String, owner: String, nowEpochMs: Long, expiresAtEpochMs: Long): Boolean
    suspend fun updateProgress(
        artifactId: String,
        bytesReceived: Long,
        entityTag: String?,
        lastModified: String?,
        nowEpochMs: Long,
    ): Boolean
    suspend fun transitionArtifact(
        artifactId: String,
        state: DownloadArtifactState,
        failureCode: DownloadFailureCode?,
        nowEpochMs: Long,
    ): Boolean
    suspend fun setUserIntent(batchId: String, intent: DownloadUserIntent, nowEpochMs: Long): Boolean
    suspend fun setPlatformTaskId(artifactId: String, platformTaskId: String?, nowEpochMs: Long): Boolean
    suspend fun releaseLease(artifactId: String, owner: String, nowEpochMs: Long): Boolean
    suspend fun clearAll()
}
