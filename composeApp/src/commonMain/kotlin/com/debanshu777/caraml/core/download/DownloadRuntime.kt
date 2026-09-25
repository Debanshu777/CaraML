package com.debanshu777.caraml.core.download

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

class DownloadRuntime(
    private val reconciler: DownloadReconciler,
    private val scheduler: PlatformDownloadScheduler,
    private val scope: CoroutineScope,
) {
    private val startup: Deferred<Unit> = scope.async(start = CoroutineStart.LAZY) {
        reconciler.reconcile()
    }

    fun start() {
        startup.start()
    }

    suspend fun awaitStartupReconciliation() {
        startup.await()
    }

    suspend fun close() {
        startup.await()
        scheduler.reconcile(emptySet())
    }
}
