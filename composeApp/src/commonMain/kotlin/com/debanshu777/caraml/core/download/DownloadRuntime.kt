package com.debanshu777.caraml.core.download

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class DownloadRuntime(
    private val reconciler: DownloadReconciler,
    private val scheduler: PlatformDownloadScheduler,
    private val scope: CoroutineScope,
) {
    private var startup: Job? = null

    fun start() {
        if (startup != null) return
        startup = scope.launch { reconciler.reconcile() }
    }

    suspend fun close() {
        startup?.join()
        scheduler.reconcile(emptySet())
    }
}
