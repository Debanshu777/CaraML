package com.debanshu777.caraml.core.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock

/** Repairs process-death state and hands durable runnable batches back to the platform. */
class DownloadReconciler(
    private val store: DownloadTaskStore,
    private val scheduler: PlatformDownloadScheduler,
    private val clock: () -> Long,
    private val publishedDownloads: PublishedDownloadReconciler? = null,
    private val checkpointCleaner: DownloadCheckpointCleaner,
    private val lifecycle: DownloadLifecycleCoordinator = DownloadLifecycleCoordinator.process,
) {
    suspend fun reconcile() {
        lifecycle.mutex.withLock {
            val discovered = store.recoverableBatches()
            scheduler.reconcile(discovered.mapTo(mutableSetOf(), DownloadBatchSnapshot::batchId))
        }
        // File proof may hash large artifacts. Its publication locks and full-observation CAS
        // already protect it, so user commands need not wait for unrelated installed bytes.
        publishedDownloads?.reconcile()
        val batches = store.recoverableBatches()
        batches.forEach { observed ->
            lifecycle.mutex.withLock {
                // Commands and platform reconciliation may have changed this row since discovery.
                val batch = store.getBatch(observed.batchId) ?: return@withLock
                if (batch.state in setOf(DownloadBatchState.COMPLETED, DownloadBatchState.CANCELLED,
                        DownloadBatchState.FAILED_TERMINAL) && !batch.cancellationCleanupPending) return@withLock
                when (batch.userIntent) {
                    DownloadUserIntent.CANCEL -> {
                        try {
                            scheduler.cancel(batch.batchId)
                            finishCancellationCleanup(store, checkpointCleaner, batch.batchId, clock)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // The durable pending marker keeps failed cleanup visible and retryable.
                            // One failed cancellation must not prevent other downloads from recovering.
                        }
                    }
                    DownloadUserIntent.PAUSE -> {
                        scheduler.pause(batch.batchId)
                        val stopped = store.getBatch(batch.batchId) ?: return@withLock
                        stopped.artifacts.filter { it.state.canPauseTransfer() }.forEach { artifact ->
                            store.transitionArtifact(artifact.artifactId, DownloadArtifactState.PAUSED, null, clock())
                        }
                    }
                    DownloadUserIntent.RUN -> {
                        val platformStillOwnsBatch = scheduler.isActive(batch.batchId)
                        var pauseOrphan = false
                        batch.artifacts.forEach { artifact ->
                            when (artifact.state) {
                                DownloadArtifactState.RUNNING -> if (!platformStillOwnsBatch) {
                                    when (scheduler.orphanedRunningDisposition(batch.batchId)) {
                                        OrphanedDownloadDisposition.PAUSE -> {
                                            pauseOrphan = true
                                            store.transitionArtifact(
                                                artifact.artifactId,
                                                DownloadArtifactState.PAUSED,
                                                null,
                                                clock(),
                                            )
                                        }
                                        OrphanedDownloadDisposition.RETRY -> {
                                            store.transitionArtifact(
                                                artifact.artifactId,
                                                DownloadArtifactState.FAILED_RETRYABLE,
                                                DownloadFailureCode.PLATFORM,
                                                clock(),
                                            )
                                            store.transitionArtifact(
                                                artifact.artifactId,
                                                DownloadArtifactState.QUEUED,
                                                null,
                                                clock(),
                                            )
                                        }
                                    }
                                }
                                DownloadArtifactState.PAUSED,
                                DownloadArtifactState.WAITING_FOR_NETWORK,
                                DownloadArtifactState.FAILED_RETRYABLE,
                                -> store.transitionArtifact(artifact.artifactId, DownloadArtifactState.QUEUED, null, clock())
                                else -> Unit
                            }
                        }
                        if (pauseOrphan) {
                            store.setUserIntent(batch.batchId, DownloadUserIntent.PAUSE, clock())
                        } else if (!platformStillOwnsBatch) {
                            scheduler.enqueue(batch.batchId)
                        }
                    }
                }
            }
        }
    }
}
