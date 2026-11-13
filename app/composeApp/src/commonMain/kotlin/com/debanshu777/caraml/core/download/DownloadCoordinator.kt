package com.debanshu777.caraml.core.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    suspend fun discard(metadata: com.debanshu777.caraml.core.download.transfer.DownloadMetadataDTO)
}

class DownloadManagerCheckpointCleaner(
    private val manager: com.debanshu777.caraml.core.download.transfer.DownloadManager,
) : DownloadCheckpointCleaner {
    override suspend fun discard(metadata: com.debanshu777.caraml.core.download.transfer.DownloadMetadataDTO) {
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

    fun observeQueue(): Flow<List<DownloadBatchSnapshot>> = store.observeQueue()

    private val commands = Mutex()

    suspend fun enqueue(request: DownloadBatchRequest): String = commands.withLock {
        val batchId = store.create(request, nowEpochMs())
        runCatching { notifications.requestIfNeeded() }
        schedule(batchId)
        batchId
    }

    suspend fun pause(batchId: String): Unit = commands.withLock {
        if (!store.setUserIntent(batchId, DownloadUserIntent.PAUSE, nowEpochMs())) return@withLock
        scheduler.pause(batchId)
        store.getBatch(batchId)?.artifacts?.filter { it.state.canPauseTransfer() }?.forEach { artifact ->
            store.transitionArtifact(artifact.artifactId, DownloadArtifactState.PAUSED, null, nowEpochMs())
        }
    }

    suspend fun resume(batchId: String) = commands.withLock { restart(batchId, retry = false) }

    suspend fun retry(batchId: String) = commands.withLock { restart(batchId, retry = true) }

    private suspend fun restart(batchId: String, retry: Boolean) {
        val batch = store.getBatch(batchId) ?: return
        if (batch.artifacts.any { !it.request.metadata.usesImmutableStorageLayout }) return
        if (!store.setUserIntent(batchId, DownloadUserIntent.RUN, nowEpochMs())) return
        batch.artifacts.filter {
            it.state == DownloadArtifactState.PAUSED ||
                (retry && it.state == DownloadArtifactState.FAILED_RETRYABLE)
        }.forEach { artifact ->
            store.transitionArtifact(artifact.artifactId, DownloadArtifactState.QUEUED, null, nowEpochMs())
        }
        schedule(batchId)
    }

    suspend fun cancel(batchId: String): Unit = commands.withLock {
        if (!store.setUserIntent(batchId, DownloadUserIntent.CANCEL, nowEpochMs())) return@withLock
        scheduler.cancel(batchId)
        store.getBatch(batchId)?.artifacts?.forEach { artifact ->
            val terminal = artifact.state.isTerminal()
            if (!terminal || artifact.state == DownloadArtifactState.CANCELLED) {
                try {
                    checkpointCleaner.discard(artifact.request.metadata)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Checkpoint cleanup is best-effort after the platform has stopped.
                }
            }
            if (!terminal) {
                store.transitionArtifact(artifact.artifactId, DownloadArtifactState.CANCELLED, null, nowEpochMs())
            }
        }
    }

    private suspend fun schedule(batchId: String) {
        try {
            scheduler.enqueue(batchId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            val batch = store.getBatch(batchId) ?: return
            batch.artifacts.filter { it.state == DownloadArtifactState.QUEUED }.forEach { artifact ->
                store.markSchedulingFailed(artifact.artifactId, nowEpochMs())
            }
        }
    }
}

internal fun DownloadArtifactState.canPauseTransfer(): Boolean = when (this) {
    DownloadArtifactState.QUEUED,
    DownloadArtifactState.RUNNING,
    DownloadArtifactState.WAITING_FOR_NETWORK,
    -> true
    else -> false
}

private fun DownloadArtifactState.isTerminal(): Boolean = this in setOf(
    DownloadArtifactState.COMPLETED,
    DownloadArtifactState.FAILED_TERMINAL,
    DownloadArtifactState.CANCELLED,
)
