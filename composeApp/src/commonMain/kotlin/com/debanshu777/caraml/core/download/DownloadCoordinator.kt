package com.debanshu777.caraml.core.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

interface PlatformDownloadScheduler {
    suspend fun enqueue(batchId: String)
    suspend fun pause(batchId: String)
    suspend fun cancel(batchId: String)
    suspend fun reconcile(liveBatchIds: Set<String>)
    suspend fun isActive(batchId: String): Boolean = false
    suspend fun orphanedRunningDisposition(batchId: String): OrphanedDownloadDisposition =
        OrphanedDownloadDisposition.RETRY
}

enum class OrphanedDownloadDisposition { RETRY, PAUSE }

fun interface DownloadNotificationPermissionController {
    fun requestIfNeeded()
}

fun interface DownloadCheckpointCleaner {
    suspend fun discard(metadata: com.debanshu777.huggingfacemanager.download.DownloadMetadataDTO)
}

class DownloadManagerCheckpointCleaner(
    private val manager: com.debanshu777.huggingfacemanager.download.DownloadManager,
) : DownloadCheckpointCleaner {
    override suspend fun discard(metadata: com.debanshu777.huggingfacemanager.download.DownloadMetadataDTO) {
        manager.discardCheckpoint(metadata)
    }
}

/**
 * Owns user-visible download commands and durably records them before asking a
 * platform scheduler to do work. The persistent store remains the source of
 * truth if a process dies between either operation.
 */
class DownloadCoordinator(
    private val store: DownloadTaskStore,
    private val scheduler: PlatformDownloadScheduler,
    private val notifications: DownloadNotificationPermissionController,
    private val checkpointCleaner: DownloadCheckpointCleaner,
    private val nowEpochMs: () -> Long,
) {
    fun observeForModel(modelId: String): Flow<List<DownloadBatchSnapshot>> =
        store.observeForModel(modelId)

    suspend fun enqueue(request: DownloadBatchRequest): String {
        val batchId = store.create(request, nowEpochMs())
        runCatching { notifications.requestIfNeeded() }
        try {
            scheduler.enqueue(batchId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            markRetryablePlatformFailure(batchId)
        }
        return batchId
    }

    suspend fun pause(batchId: String) {
        if (store.setUserIntent(batchId, DownloadUserIntent.PAUSE, nowEpochMs())) {
            store.getBatch(batchId)?.artifacts
                ?.filter { it.state in setOf(
                    DownloadArtifactState.QUEUED,
                    DownloadArtifactState.RUNNING,
                    DownloadArtifactState.WAITING_FOR_NETWORK,
                ) }
                ?.forEach { artifact ->
                    store.transitionArtifact(artifact.artifactId, DownloadArtifactState.PAUSED, null, nowEpochMs())
                }
            scheduler.pause(batchId)
        }
    }

    suspend fun resume(batchId: String) {
        val batch = store.getBatch(batchId) ?: return
        if (batch.artifacts.any { !it.request.metadata.usesImmutableStorageLayout }) return
        if (store.setUserIntent(batchId, DownloadUserIntent.RUN, nowEpochMs())) {
            store.getBatch(batchId)?.artifacts
                ?.filter { it.state == DownloadArtifactState.PAUSED }
                ?.forEach { artifact ->
                    store.transitionArtifact(artifact.artifactId, DownloadArtifactState.QUEUED, null, nowEpochMs())
                }
            scheduler.enqueue(batchId)
        }
    }

    suspend fun cancel(batchId: String) {
        if (!store.setUserIntent(batchId, DownloadUserIntent.CANCEL, nowEpochMs())) return
        scheduler.cancel(batchId)
        store.getBatch(batchId)?.artifacts
            ?.filterNot { it.state.isTerminal() }
            ?.forEach { artifact ->
                runCatching { checkpointCleaner.discard(artifact.request.metadata) }
                store.transitionArtifact(
                    artifactId = artifact.artifactId,
                    state = DownloadArtifactState.CANCELLED,
                    failureCode = null,
                    nowEpochMs = nowEpochMs(),
                )
            }
    }

    suspend fun retry(batchId: String) {
        val batch = store.getBatch(batchId) ?: return
        batch.artifacts
            .filter { it.state == DownloadArtifactState.FAILED_RETRYABLE }
            .forEach { artifact ->
                store.transitionArtifact(
                    artifactId = artifact.artifactId,
                    state = DownloadArtifactState.QUEUED,
                    failureCode = null,
                    nowEpochMs = nowEpochMs(),
                )
            }
        resume(batchId)
    }

    private suspend fun markRetryablePlatformFailure(batchId: String) {
        store.getBatch(batchId)?.artifacts
            ?.filterNot { it.state.isTerminal() }
            ?.forEach { artifact ->
                store.transitionArtifact(
                    artifactId = artifact.artifactId,
                    state = DownloadArtifactState.FAILED_RETRYABLE,
                    failureCode = DownloadFailureCode.PLATFORM,
                    nowEpochMs = nowEpochMs(),
                )
            }
    }
}

private fun DownloadArtifactState.isTerminal(): Boolean = this in setOf(
    DownloadArtifactState.COMPLETED,
    DownloadArtifactState.FAILED_TERMINAL,
    DownloadArtifactState.CANCELLED,
)
