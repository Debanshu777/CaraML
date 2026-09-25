package com.debanshu777.caraml.features.modelhub.presentation

import com.debanshu777.caraml.core.download.DownloadArtifactState
import com.debanshu777.caraml.core.download.DownloadBatchRequest
import com.debanshu777.caraml.core.download.DownloadBatchSnapshot
import com.debanshu777.caraml.core.download.DownloadCheckpointCleaner
import com.debanshu777.caraml.core.download.DownloadCoordinator
import com.debanshu777.caraml.core.download.DownloadFailureCode
import com.debanshu777.caraml.core.download.DownloadNotificationPermissionController
import com.debanshu777.caraml.core.download.DownloadTaskStore
import com.debanshu777.caraml.core.download.DownloadUserIntent
import com.debanshu777.caraml.core.download.PlatformDownloadScheduler
import kotlinx.coroutines.flow.flowOf

internal fun modelHubTestDownloadCoordinator(): DownloadCoordinator = DownloadCoordinator(
    store = InertDownloadTaskStore,
    scheduler = object : PlatformDownloadScheduler {
        override suspend fun enqueue(batchId: String) = Unit
        override suspend fun pause(batchId: String) = Unit
        override suspend fun cancel(batchId: String) = Unit
        override suspend fun reconcile(liveBatchIds: Set<String>) = Unit
    },
    notifications = DownloadNotificationPermissionController {},
    checkpointCleaner = DownloadCheckpointCleaner {},
    nowEpochMs = { 0L },
)

private object InertDownloadTaskStore : DownloadTaskStore {
    override suspend fun create(request: DownloadBatchRequest, nowEpochMs: Long): String = "test-batch"
    override fun observeForModel(modelId: String) = flowOf(emptyList<DownloadBatchSnapshot>())
    override suspend fun getBatch(batchId: String): DownloadBatchSnapshot? = null
    override suspend fun recoverableBatches(): List<DownloadBatchSnapshot> = emptyList()
    override suspend fun claim(
        artifactId: String,
        owner: String,
        nowEpochMs: Long,
        expiresAtEpochMs: Long,
    ): Boolean = false

    override suspend fun updateProgress(
        artifactId: String,
        bytesReceived: Long,
        entityTag: String?,
        lastModified: String?,
        nowEpochMs: Long,
    ): Boolean = false

    override suspend fun transitionArtifact(
        artifactId: String,
        state: DownloadArtifactState,
        failureCode: DownloadFailureCode?,
        nowEpochMs: Long,
    ): Boolean = false

    override suspend fun setUserIntent(
        batchId: String,
        intent: DownloadUserIntent,
        nowEpochMs: Long,
    ): Boolean = false

    override suspend fun setPlatformTaskId(
        artifactId: String,
        platformTaskId: String?,
        nowEpochMs: Long,
    ): Boolean = false

    override suspend fun releaseLease(
        artifactId: String,
        owner: String,
        nowEpochMs: Long,
    ): Boolean = false

    override suspend fun clearAll() = Unit
}
