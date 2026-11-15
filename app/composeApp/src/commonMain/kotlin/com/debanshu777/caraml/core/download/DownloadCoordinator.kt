package com.debanshu777.caraml.core.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface PlatformDownloadScheduler {
    /** Call outside the lifecycle lock: restoration needs that lock before scheduling is ready. */
    suspend fun awaitReadyForScheduling() = Unit
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
    private val lifecycle: DownloadLifecycleCoordinator = DownloadLifecycleCoordinator.process,
) {
    fun observeForModel(modelId: String): Flow<List<DownloadBatchSnapshot>> =
        store.observeForModel(modelId)

    fun observeQueue(): Flow<List<DownloadBatchSnapshot>> = store.observeQueue()

    /** Commands resolve and revalidate exact persisted identity independently of the visible queue. */
    suspend fun getBatch(batchId: String): DownloadBatchSnapshot? = store.getBatch(batchId)

    suspend fun enqueue(request: DownloadBatchRequest): String {
        val batchId = lifecycle.mutex.withLock {
            val batchId = store.create(request, nowEpochMs())
            if (store.getBatch(batchId)?.cancellationCleanupPending == true) {
                scheduler.cancel(batchId)
                finishCancellationCleanup(store, checkpointCleaner, batchId, nowEpochMs)
                store.create(request, nowEpochMs())
            }
            runCatching { notifications.requestIfNeeded() }
            batchId
        }
        scheduleWhenReady(batchId)
        return batchId
    }

    // Stop intent must remain available while platform restoration is pending.
    suspend fun pause(batchId: String): Unit = lifecycle.mutex.withLock {
        if (!store.setUserIntent(batchId, DownloadUserIntent.PAUSE, nowEpochMs())) return@withLock
        scheduler.pause(batchId)
        store.getBatch(batchId)?.artifacts?.filter { it.state.canPauseTransfer() }?.forEach { artifact ->
            store.transitionArtifact(artifact.artifactId, DownloadArtifactState.PAUSED, null, nowEpochMs())
        }
    }

    suspend fun resume(batchId: String) {
        if (lifecycle.mutex.withLock { restart(batchId, retry = false) }) scheduleWhenReady(batchId)
    }

    suspend fun retry(batchId: String) {
        if (lifecycle.mutex.withLock { restart(batchId, retry = true) }) scheduleWhenReady(batchId)
    }

    private suspend fun restart(batchId: String, retry: Boolean): Boolean {
        val batch = store.getBatch(batchId) ?: return false
        if (batch.artifacts.any { !it.request.metadata.usesImmutableStorageLayout }) return false
        if (!store.setUserIntent(batchId, DownloadUserIntent.RUN, nowEpochMs())) return false
        batch.artifacts.filter {
            it.state == DownloadArtifactState.PAUSED ||
                (retry && it.state == DownloadArtifactState.FAILED_RETRYABLE)
        }.forEach { artifact ->
            store.transitionArtifact(artifact.artifactId, DownloadArtifactState.QUEUED, null, nowEpochMs())
        }
        return true
    }

    suspend fun cancel(batchId: String): Unit = lifecycle.mutex.withLock {
        if (!store.setUserIntent(batchId, DownloadUserIntent.CANCEL, nowEpochMs())) return@withLock
        scheduler.cancel(batchId)
        finishCancellationCleanup(store, checkpointCleaner, batchId, nowEpochMs)
    }

    private suspend fun scheduleWhenReady(batchId: String) {
        // Intent is already durable. Restoration needs the lifecycle lock, and a newer stop must
        // remain authoritative when this wait finishes; never replay intent after waiting.
        scheduler.awaitReadyForScheduling()
        lifecycle.mutex.withLock {
            val current = store.getBatch(batchId) ?: return@withLock
            if (current.userIntent == DownloadUserIntent.RUN && !current.cancellationCleanupPending) {
                schedule(batchId)
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

/** Checkpoint deletion is idempotent; persist completion only after all stopped work was cleaned. */
internal suspend fun finishCancellationCleanup(
    store: DownloadTaskStore,
    cleaner: DownloadCheckpointCleaner,
    batchId: String,
    clock: () -> Long,
) {
    repeat(2) {
        val observed = store.pendingCancellation(batchId) ?: return
        observed.batch.artifacts.filter { it.state != DownloadArtifactState.COMPLETED }.forEach { artifact ->
            cleaner.discard(artifact.request.metadata)
        }
        if (store.completeCancellationCleanup(observed, clock())) return
    }
    error("Cancelled download changed while checkpoint cleanup was completing")
}

/** Process-local commands and recovery share the fence around irreversible checkpoint cleanup. */
class DownloadLifecycleCoordinator {
    internal val mutex = Mutex()

    companion object {
        // Alternate construction paths must share the fence too; DI may inject an explicit scope.
        internal val process = DownloadLifecycleCoordinator()
    }
}
