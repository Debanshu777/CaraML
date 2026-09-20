package com.debanshu777.caraml.core.download

import com.debanshu777.huggingfacemanager.download.DownloadArtifactIdentity
import com.debanshu777.huggingfacemanager.download.DownloadMetadataDTO
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadRuntimeReconciliationTest {
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun startupBarrierDoesNotOpenUntilReconciliationCompletes() = runTest {
        val reconciliationEntered = CompletableDeferred<Unit>()
        val releaseReconciliation = CompletableDeferred<Unit>()
        val scheduler = ReconciliationScheduler(
            active = true,
            reconciliationEntered = reconciliationEntered,
            releaseReconciliation = releaseReconciliation,
        )
        val reconciler = DownloadReconciler(
            store = ReconciliationStore(runningBatch()),
            scheduler = scheduler,
            clock = { 10L },
        )
        val runtime = DownloadRuntime(reconciler, scheduler, backgroundScope)

        runtime.start()
        reconciliationEntered.await()
        val waiter = async { runtime.awaitStartupReconciliation() }
        runCurrent()
        assertFalse(waiter.isCompleted)

        releaseReconciliation.complete(Unit)
        waiter.await()
        assertTrue(waiter.isCompleted)
    }

    @Test
    fun livePlatformOwnerKeepsRunningLeaseAndIsNotEnqueuedAgain() = runTest {
        val store = ReconciliationStore(runningBatch())
        val scheduler = ReconciliationScheduler(active = true)

        DownloadReconciler(store, scheduler, { 10L }).reconcile()

        assertEquals(emptyList(), store.transitions)
        assertEquals(emptyList(), store.intents)
        assertEquals(0, scheduler.enqueueCalls)
    }

    @Test
    fun orphanedUidtRunningBatchBecomesPausedWithoutAutomaticRestart() = runTest {
        val store = ReconciliationStore(runningBatch())
        val scheduler = ReconciliationScheduler(
            active = false,
            orphanedDisposition = OrphanedDownloadDisposition.PAUSE,
        )

        DownloadReconciler(store, scheduler, { 10L }).reconcile()

        assertEquals(listOf(DownloadUserIntent.PAUSE), store.intents)
        assertEquals(listOf(DownloadArtifactState.PAUSED), store.transitions)
        assertEquals(0, scheduler.enqueueCalls)
    }

    private fun runningBatch(): DownloadBatchSnapshot {
        val identity = requireNotNull(
            DownloadArtifactIdentity.create(
                repositoryId = "owner/model",
                immutableRevision = "a".repeat(40),
                relativePath = "model.gguf",
                remoteObjectId = "b".repeat(64),
                expectedBytes = 10L,
            ),
        )
        val request = DownloadArtifactRequest(
            metadata = DownloadMetadataDTO(identity, "model", 10L, null, null, null),
            primary = true,
        )
        return DownloadBatchSnapshot(
            batchId = "c".repeat(64),
            ownerModelId = identity.repositoryId,
            modelType = "text",
            displayName = "Model",
            state = DownloadBatchState.RUNNING,
            userIntent = DownloadUserIntent.RUN,
            artifacts = listOf(
                DownloadArtifactSnapshot(
                    artifactId = "artifact",
                    batchId = "c".repeat(64),
                    request = request,
                    state = DownloadArtifactState.RUNNING,
                    userIntent = DownloadUserIntent.RUN,
                    bytesReceived = 1L,
                    expectedBytes = 10L,
                ),
            ),
            evidence = pendingEvidence(identity),
        )
    }
}

private class ReconciliationScheduler(
    private val active: Boolean,
    private val orphanedDisposition: OrphanedDownloadDisposition = OrphanedDownloadDisposition.RETRY,
    private val reconciliationEntered: CompletableDeferred<Unit>? = null,
    private val releaseReconciliation: CompletableDeferred<Unit>? = null,
) : PlatformDownloadScheduler {
    var enqueueCalls = 0
    override suspend fun enqueue(batchId: String) { enqueueCalls += 1 }
    override suspend fun pause(batchId: String) = Unit
    override suspend fun cancel(batchId: String) = Unit
    override suspend fun reconcile(liveBatchIds: Set<String>) {
        reconciliationEntered?.complete(Unit)
        releaseReconciliation?.await()
    }
    override suspend fun isActive(batchId: String): Boolean = active
    override suspend fun orphanedRunningDisposition(batchId: String): OrphanedDownloadDisposition = orphanedDisposition
}

private class ReconciliationStore(
    private var snapshot: DownloadBatchSnapshot,
) : DownloadTaskStore {
    val transitions = mutableListOf<DownloadArtifactState>()
    val intents = mutableListOf<DownloadUserIntent>()

    override suspend fun create(request: DownloadBatchRequest, nowEpochMs: Long) = snapshot.batchId
    override fun observeForModel(modelId: String): Flow<List<DownloadBatchSnapshot>> = flowOf(listOf(snapshot))
    override suspend fun getBatch(batchId: String): DownloadBatchSnapshot = snapshot
    override suspend fun recoverableBatches(): List<DownloadBatchSnapshot> = listOf(snapshot)
    override suspend fun claim(artifactId: String, owner: String, nowEpochMs: Long, expiresAtEpochMs: Long) = false
    override suspend fun updateProgress(
        artifactId: String,
        bytesReceived: Long,
        entityTag: String?,
        lastModified: String?,
        nowEpochMs: Long,
    ) = false

    override suspend fun transitionArtifact(
        artifactId: String,
        state: DownloadArtifactState,
        failureCode: DownloadFailureCode?,
        nowEpochMs: Long,
    ): Boolean {
        transitions += state
        snapshot = snapshot.copy(
            state = if (state == DownloadArtifactState.PAUSED) DownloadBatchState.PAUSED else snapshot.state,
            artifacts = snapshot.artifacts.map { it.copy(state = state) },
        )
        return true
    }

    override suspend fun setUserIntent(batchId: String, intent: DownloadUserIntent, nowEpochMs: Long): Boolean {
        intents += intent
        snapshot = snapshot.copy(
            userIntent = intent,
            artifacts = snapshot.artifacts.map { it.copy(userIntent = intent) },
        )
        return true
    }

    override suspend fun setPlatformTaskId(artifactId: String, platformTaskId: String?, nowEpochMs: Long) = false
    override suspend fun releaseLease(artifactId: String, owner: String, nowEpochMs: Long) = false
    override suspend fun clearAll() = Unit
}
