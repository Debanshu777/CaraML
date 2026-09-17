package com.debanshu777.caraml.core.download

/** Repairs process-death state and hands durable runnable batches back to the platform. */
class DownloadReconciler(
    private val store: DownloadTaskStore,
    private val scheduler: PlatformDownloadScheduler,
    private val clock: () -> Long,
) {
    suspend fun reconcile() {
        val batches = store.recoverableBatches()
        scheduler.reconcile(batches.mapTo(mutableSetOf(), DownloadBatchSnapshot::batchId))
        batches.forEach { batch ->
            when (batch.userIntent) {
                DownloadUserIntent.CANCEL -> {
                    scheduler.cancel(batch.batchId)
                    transitionNonterminal(batch, DownloadArtifactState.CANCELLED)
                }
                DownloadUserIntent.PAUSE -> {
                    scheduler.pause(batch.batchId)
                    batch.artifacts.filter { it.state == DownloadArtifactState.RUNNING }.forEach { artifact ->
                        store.transitionArtifact(artifact.artifactId, DownloadArtifactState.PAUSED, null, clock())
                    }
                }
                DownloadUserIntent.RUN -> {
                    val platformStillOwnsBatch = scheduler.isActive(batch.batchId)
                    batch.artifacts.forEach { artifact ->
                        when (artifact.state) {
                            DownloadArtifactState.RUNNING -> if (!platformStillOwnsBatch) {
                                store.transitionArtifact(
                                    artifact.artifactId,
                                    DownloadArtifactState.FAILED_RETRYABLE,
                                    DownloadFailureCode.PLATFORM,
                                    clock(),
                                )
                                store.transitionArtifact(artifact.artifactId, DownloadArtifactState.QUEUED, null, clock())
                            }
                            DownloadArtifactState.WAITING_FOR_NETWORK,
                            DownloadArtifactState.FAILED_RETRYABLE,
                            -> store.transitionArtifact(artifact.artifactId, DownloadArtifactState.QUEUED, null, clock())
                            else -> Unit
                        }
                    }
                    scheduler.enqueue(batch.batchId)
                }
            }
        }
    }

    private suspend fun transitionNonterminal(batch: DownloadBatchSnapshot, next: DownloadArtifactState) {
        batch.artifacts.filterNot { it.state in TERMINAL_STATES }.forEach { artifact ->
            store.transitionArtifact(artifact.artifactId, next, null, clock())
        }
    }

    private companion object {
        val TERMINAL_STATES = setOf(
            DownloadArtifactState.COMPLETED,
            DownloadArtifactState.FAILED_TERMINAL,
            DownloadArtifactState.CANCELLED,
        )
    }
}
