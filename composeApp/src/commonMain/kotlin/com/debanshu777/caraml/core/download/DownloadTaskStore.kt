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
    suspend fun transitionPlatformTask(
        artifactId: String,
        platformTaskId: String,
        state: DownloadArtifactState,
        failureCode: DownloadFailureCode?,
        completedBytes: Long?,
        nowEpochMs: Long,
    ): Boolean = false
    suspend fun bindPlatformTask(batchId: String, platformTaskId: String, nowEpochMs: Long): Boolean = false
    suspend fun pausePlatformTask(batchId: String, platformTaskId: String, nowEpochMs: Long): Boolean = false
    suspend fun checkpointCancellation(
        batchId: String,
        artifactId: String,
        owner: String,
        nowEpochMs: Long,
    ): Boolean {
        val batch = getBatch(batchId) ?: return false
        val artifact = batch.artifacts.firstOrNull { it.artifactId == artifactId } ?: return false
        val next = when (batch.userIntent) {
            DownloadUserIntent.PAUSE -> DownloadArtifactState.PAUSED
            DownloadUserIntent.CANCEL -> DownloadArtifactState.CANCELLED
            DownloadUserIntent.RUN -> DownloadArtifactState.FAILED_RETRYABLE
        }
        val failure = DownloadFailureCode.NETWORK.takeIf { batch.userIntent == DownloadUserIntent.RUN }
        return try {
            if (artifact.state != DownloadArtifactState.RUNNING) false
            else transitionArtifact(artifactId, next, failure, nowEpochMs)
        } finally {
            releaseLease(artifactId, owner, nowEpochMs)
        }
    }
    suspend fun releaseLease(artifactId: String, owner: String, nowEpochMs: Long): Boolean
    suspend fun clearAll()
}
