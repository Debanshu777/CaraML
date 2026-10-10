package com.debanshu777.caraml.core.download

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

interface DownloadTaskStore {
    suspend fun create(request: DownloadBatchRequest, nowEpochMs: Long): String
    fun observeForModel(modelId: String): Flow<List<DownloadBatchSnapshot>>
    /** Recent actionable and failed work across models, bounded by the store. */
    fun observeQueue(): Flow<List<DownloadBatchSnapshot>> = flowOf(emptyList())
    suspend fun getBatch(batchId: String): DownloadBatchSnapshot?
    suspend fun recoverableBatches(): List<DownloadBatchSnapshot>
    /** Bounded observations eligible for exact installed publication reconciliation. */
    suspend fun publishedDownloadCandidates(nowEpochMs: Long): List<PublishedDownloadSnapshot> = emptyList()
    suspend fun scanPublishedDownloads(nowEpochMs: Long, afterBatchId: String?): PublishedDownloadScanPage {
        val candidates = publishedDownloadCandidates(nowEpochMs).filter {
            afterBatchId == null || it.batch.batchId > afterBatchId
        }.sortedBy { it.batch.batchId }.take(64)
        return PublishedDownloadScanPage(candidates, candidates.lastOrNull()?.batch?.batchId)
    }
    /** Completes only the unchanged observed work; it never changes user intent or checkpoints. */
    suspend fun completePublishedDownload(expected: PublishedDownloadSnapshot, nowEpochMs: Long): Boolean = false
    suspend fun claim(artifactId: String, owner: String, nowEpochMs: Long, expiresAtEpochMs: Long): Boolean
    /** Restarts the active transfer checkpoint only while its exact current transfer lease owns RUNNING work. */
    suspend fun restartTransferCheckpoint(artifactId: String, owner: String, previousBytes: Long, nowEpochMs: Long): Boolean = false
    /** Publishes progress and renews only the exact live transfer owner's lease atomically. */
    suspend fun updateTransferProgress(artifactId: String, owner: String, bytesReceived: Long,
        entityTag: String?, lastModified: String?, nowEpochMs: Long, expiresAtEpochMs: Long): Boolean = false
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
    suspend fun requeueMissingCompletedArtifact(artifactId: String, nowEpochMs: Long): Boolean = false
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
