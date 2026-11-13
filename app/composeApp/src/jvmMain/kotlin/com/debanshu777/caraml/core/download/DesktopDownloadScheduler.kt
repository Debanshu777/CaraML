package com.debanshu777.caraml.core.download

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

class DesktopDownloadScheduler(
    private val runner: DownloadBatchRunner,
    private val scope: CoroutineScope,
) : PlatformDownloadScheduler {
    private val permits = Semaphore(2)
    private val lock = Mutex()
    private val jobs = mutableMapOf<String, Job>()

    override suspend fun enqueue(batchId: String) {
        lock.withLock {
            if (jobs[batchId]?.isActive == true) return
            jobs[batchId] = scope.launch {
                val owner = coroutineContext[Job]
                try {
                    permits.withPermit { runner.run(batchId) {} }
                } finally {
                    withContext(NonCancellable) {
                        lock.withLock {
                            if (jobs[batchId] === owner) jobs.remove(batchId)
                        }
                    }
                }
            }
        }
    }

    override suspend fun isActive(batchId: String): Boolean = lock.withLock { jobs[batchId]?.isActive == true }

    override suspend fun pause(batchId: String) = stop(batchId)

    override suspend fun cancel(batchId: String) = stop(batchId)

    override suspend fun reconcile(liveBatchIds: Set<String>) {
        val obsolete = lock.withLock { jobs.filterKeys { it !in liveBatchIds }.values.toList() }
        obsolete.forEach { it.cancelAndJoin() }
    }

    private suspend fun stop(batchId: String) {
        val job = lock.withLock { jobs.remove(batchId) }
        job?.cancelAndJoin()
    }
}

object DesktopDownloadNotificationPermissionController : DownloadNotificationPermissionController {
    override fun requestIfNeeded() = Unit
}
