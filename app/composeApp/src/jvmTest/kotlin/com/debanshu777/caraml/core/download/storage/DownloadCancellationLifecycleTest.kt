package com.debanshu777.caraml.core.download.storage

import com.debanshu777.caraml.core.download.*
import com.debanshu777.caraml.core.download.transfer.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadCancellationLifecycleTest {
    @Test
    fun explicitEnqueueReactivatesCompletedBatchAndChecksPublicationBeforeReinstall() = runTest {
        listOf(false, true).forEach { keepPublishedFile ->
            val root = Files.createTempDirectory("caraml-completed-reinstall").toFile()
            val database = getDownloadRoomDatabase(getDownloadDatabaseBuilder(File(root, "downloads.db").path))
            try {
                val store = RoomDownloadTaskStore(database.downloadTaskDao())
                val request = lifecycleRequest()
                val id = store.create(request, 1L)
                val artifact = assertNotNull(store.getBatch(id)).artifacts.single()
                assertTrue(store.claim(artifact.artifactId, "original", 2L, 50L))
                assertTrue(store.updateTransferProgress(artifact.artifactId, "original", artifact.expectedBytes,
                    "original-validator", null, 3L, 50L))
                assertTrue(store.transitionArtifact(artifact.artifactId, DownloadArtifactState.VERIFYING, null, 4L))
                assertTrue(store.transitionArtifact(artifact.artifactId, DownloadArtifactState.COMPLETED, null, 5L))
                val completed = assertNotNull(store.getBatch(id))
                val publishedFile = File(root, "model.gguf").apply { writeBytes(ByteArray(1024)) }
                if (!keepPublishedFile) assertTrue(publishedFile.delete())

                assertFalse(store.createNewOnly(request, 6L).second)
                assertEquals(completed, store.getBatch(id))
                assertTrue(store.recoverableBatches().isEmpty())
                val scheduler = LifecycleScheduler()
                val lifecycle = DownloadLifecycleCoordinator()
                val cleaner = DownloadCheckpointCleaner { error("Completed publication is not a checkpoint") }
                val coordinator = DownloadCoordinator(store, scheduler, DownloadNotificationPermissionController {},
                    cleaner, { 10L }, lifecycle)
                DownloadReconciler(store, scheduler, { 7L }, checkpointCleaner = cleaner, lifecycle = lifecycle).reconcile()
                coordinator.resume(id)
                coordinator.retry(id)
                assertEquals(DownloadBatchState.COMPLETED, store.getBatch(id)?.state)

                assertEquals(id, coordinator.enqueue(request))
                val queued = assertNotNull(store.getBatch(id))
                assertEquals(DownloadBatchState.QUEUED, queued.state)
                assertEquals(DownloadArtifactState.QUEUED, queued.artifacts.single().state)
                assertEquals(0L, queued.bytesReceived)
                assertNull(queued.artifacts.single().entityTag)
                assertEquals(completed.evidence, queued.evidence)
                assertFalse(queued.cancellationCleanupPending)
                assertTrue(id in scheduler.active)

                var transfers = 0
                var publicationChecks = 0
                var finalizations = 0
                val transfer = object : ArtifactTransfer {
                    override suspend fun isPublished(metadata: DownloadMetadataDTO): Boolean {
                        publicationChecks++
                        assertEquals(request.artifacts.single().metadata, metadata)
                        return publishedFile.isFile
                    }
                    override fun download(metadata: DownloadMetadataDTO,
                        resumeMetadata: DownloadResumeMetadata?): Flow<DownloadProgressDTO> = flow {
                        transfers++
                        assertNull(resumeMetadata)
                        publishedFile.writeBytes(ByteArray(1024))
                        emit(DownloadProgressDTO(metadata.artifact.expectedBytes, metadata.artifact.expectedBytes,
                            100f, localPath = publishedFile.path))
                    }
                }
                val runner = DownloadBatchRunner(store, transfer, BatchFinalizer { finalizations++ }, { 20L })
                assertEquals(DownloadRunResult.Completed, runner.run(id) {})
                assertEquals(if (keepPublishedFile) 0 else 1, transfers)
                assertEquals(1, publicationChecks)
                assertEquals(1, finalizations)
                assertTrue(publishedFile.isFile)
                assertEquals(DownloadBatchState.COMPLETED, store.getBatch(id)?.state)
            } finally { database.close() }
        }
    }

    @Test
    fun explicitReinstallWaitsForPendingCancellationCleanup() = runTest {
        val database = getDownloadRoomDatabase(getDownloadDatabaseBuilder(
            Files.createTempDirectory("caraml-reinstall-cleanup").resolve("downloads.db").toString()))
        try {
            val store = RoomDownloadTaskStore(database.downloadTaskDao())
            val request = lifecycleRequest()
            val id = store.create(request, 1L)
            val artifactId = assertNotNull(store.getBatch(id)).artifacts.single().artifactId
            assertTrue(store.claim(artifactId, "original", 2L, 50L))
            assertTrue(store.transitionArtifact(artifactId, DownloadArtifactState.VERIFYING, null, 3L))
            assertTrue(store.setUserIntent(id, DownloadUserIntent.CANCEL, 4L))
            assertTrue(store.transitionArtifact(artifactId, DownloadArtifactState.COMPLETED, null, 5L))
            val pending = assertNotNull(store.getBatch(id))
            assertTrue(pending.cancellationCleanupPending)

            assertEquals(id, store.create(request, 6L))
            assertEquals(pending, store.getBatch(id))
            val scheduler = LifecycleScheduler()
            val coordinator = DownloadCoordinator(store, scheduler, DownloadNotificationPermissionController {},
                DownloadCheckpointCleaner { error("Completed publication must be retained") }, { 10L })
            assertEquals(id, coordinator.enqueue(request))
            assertEquals(1, scheduler.cancellations)
            val reinstalled = assertNotNull(store.getBatch(id))
            assertEquals(DownloadBatchState.QUEUED, reinstalled.state)
            assertEquals(DownloadUserIntent.RUN, reinstalled.userIntent)
            assertFalse(reinstalled.cancellationCleanupPending)
        } finally { database.close() }
    }

    @Test
    fun schedulingCommandsBeforeRestorationDoNotBlockRecovery() = runTest {
        SchedulingCommand.entries.forEach { command ->
            withStartupFixture(command) { store, scheduler, coordinator, reconciler, request, id ->
                val pending = async { command.execute(coordinator, request, id) }
                scheduler.readinessAwaited.await()
                assertFalse(pending.isCompleted)
                assertEquals(DownloadUserIntent.RUN, store.getBatch(id)?.userIntent)
                assertEquals(DownloadBatchState.QUEUED, store.getBatch(id)?.state)

                reconciler.reconcile()
                pending.await()

                val started = assertNotNull(store.getBatch(id))
                assertEquals(DownloadUserIntent.RUN, started.userIntent)
                assertEquals(DownloadBatchState.QUEUED, started.state)
                assertTrue(id in scheduler.active)
            }
        }
    }

    @Test
    fun reconciliationBeforeSchedulingPreservesTheNewBatch() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        withStartupFixture(SchedulingCommand.ENQUEUE, beforeReconcile = {
            entered.complete(Unit)
            release.await()
        }) { store, scheduler, coordinator, reconciler, request, id ->
            val recovery = async { reconciler.reconcile() }
            entered.await()
            val pending = async { coordinator.enqueue(request) }
            runCurrent()
            assertFalse(pending.isCompleted)
            assertNull(store.getBatch(id))

            release.complete(Unit)
            recovery.await()
            assertEquals(id, pending.await())
            assertEquals(DownloadUserIntent.RUN, store.getBatch(id)?.userIntent)
            assertTrue(id in scheduler.active)
        }
    }

    @Test
    fun cancelledSchedulingWaitKeepsDurableIntentForRecovery() = runTest {
        SchedulingCommand.entries.forEach { command ->
            withStartupFixture(command) { store, scheduler, coordinator, reconciler, request, id ->
                val pending = async { command.execute(coordinator, request, id) }
                scheduler.readinessAwaited.await()
                val accepted = assertNotNull(store.getBatch(id))
                assertEquals(DownloadUserIntent.RUN, accepted.userIntent)
                assertEquals(DownloadBatchState.QUEUED, accepted.state)
                pending.cancelAndJoin()
                assertEquals(accepted, store.getBatch(id))
                assertTrue(scheduler.active.isEmpty())

                reconciler.reconcile()
                assertTrue(pending.isCancelled)
                assertEquals(DownloadUserIntent.RUN, store.getBatch(id)?.userIntent)
                assertTrue(id in scheduler.active)
            }
        }
    }

    @Test
    fun newerSameBatchStopWinsOverSchedulingWaitingForRestoration() = runTest {
        SchedulingCommand.entries.forEach { command ->
            listOf(DownloadUserIntent.PAUSE, DownloadUserIntent.CANCEL).forEach { stop ->
                withStartupFixture(command) { store, scheduler, coordinator, reconciler, request, id ->
                    if (command == SchedulingCommand.ENQUEUE) store.create(request, 1L)
                    val pending = async { command.execute(coordinator, request, id) }
                    scheduler.readinessAwaited.await()
                    assertEquals(DownloadUserIntent.RUN, store.getBatch(id)?.userIntent)

                    if (stop == DownloadUserIntent.PAUSE) {
                        coordinator.pause(id)
                        coordinator.pause(id)
                    } else {
                        coordinator.cancel(id)
                    }
                    val stopped = assertNotNull(store.getBatch(id))
                    assertEquals(stop, stopped.userIntent)
                    assertFalse(stopped.cancellationCleanupPending)

                    reconciler.reconcile()
                    pending.await()

                    assertEquals(stopped, store.getBatch(id))
                    assertFalse(id in scheduler.active)
                }
            }
        }
    }

    @Test
    fun pauseAndCancelRemainAvailableWhileSchedulingAwaitsRestoration() = runTest {
        withStartupFixture(SchedulingCommand.ENQUEUE) { store, scheduler, coordinator, reconciler, request, id ->
            val stoppedId = store.create(lifecycleRequest("stopped.gguf"), 1L)
            val pending = async { coordinator.enqueue(request) }
            scheduler.readinessAwaited.await()

            coordinator.pause(stoppedId)
            assertEquals(DownloadUserIntent.PAUSE, store.getBatch(stoppedId)?.userIntent)
            assertEquals(DownloadBatchState.PAUSED, store.getBatch(stoppedId)?.state)
            coordinator.cancel(stoppedId)
            val stopped = assertNotNull(store.getBatch(stoppedId))
            assertEquals(DownloadUserIntent.CANCEL, stopped.userIntent)
            assertEquals(DownloadBatchState.CANCELLED, stopped.state)
            assertFalse(stopped.cancellationCleanupPending)
            assertFalse(pending.isCompleted)

            reconciler.reconcile()
            assertEquals(id, pending.await())
            assertTrue(id in scheduler.active)
            assertFalse(stoppedId in scheduler.active)
        }
    }

    @Test
    fun recoveryCleanupCannotDeleteCheckpointFromConcurrentReenqueue() = runTest {
        val database = getDownloadRoomDatabase(getDownloadDatabaseBuilder(
            Files.createTempDirectory("caraml-cancel-fence").resolve("downloads.db").toString()))
        try {
            val store = RoomDownloadTaskStore(database.downloadTaskDao())
            val request = lifecycleRequest()
            val id = store.create(request, 1L)
            assertTrue(store.setUserIntent(id, DownloadUserIntent.CANCEL, 2L))
            val checkpoint = Files.createTempFile("caraml-cleanup-generation", ".part").toFile().apply { writeText("old") }
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val cleaner = DownloadCheckpointCleaner {
                entered.complete(Unit)
                release.await()
                checkpoint.delete()
            }
            val lifecycle = DownloadLifecycleCoordinator()
            val scheduler = LifecycleScheduler()
            val reconciler = DownloadReconciler(store, scheduler, { 10L }, checkpointCleaner = cleaner, lifecycle = lifecycle)
            val coordinator = DownloadCoordinator(store, scheduler, DownloadNotificationPermissionController {}, cleaner, { 20L }, lifecycle)
            val recovery = async { reconciler.reconcile() }
            entered.await()
            val restarted = async {
                coordinator.enqueue(request)
                checkpoint.writeText("new attempt")
            }
            runCurrent()
            assertFalse(restarted.isCompleted)
            release.complete(Unit)
            recovery.await()
            restarted.await()
            assertEquals("new attempt", checkpoint.readText())
            assertEquals(DownloadUserIntent.RUN, store.getBatch(id)!!.userIntent)
            assertEquals(DownloadBatchState.QUEUED, store.getBatch(id)!!.state)
            assertFalse(store.getBatch(id)!!.cancellationCleanupPending)
            assertTrue(id in scheduler.active)
            assertEquals(1, scheduler.cancellations)
        } finally { database.close() }
    }

    @Test
    fun liveSchedulerSnapshotCannotCancelConcurrentlyEnqueuedNewBatch() = runTest {
        val database = getDownloadRoomDatabase(getDownloadDatabaseBuilder(
            Files.createTempDirectory("caraml-reconcile-fence").resolve("downloads.db").toString()))
        try {
            val store = RoomDownloadTaskStore(database.downloadTaskDao())
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val lifecycle = DownloadLifecycleCoordinator()
            val scheduler = LifecycleScheduler {
                entered.complete(Unit)
                release.await()
            }
            val reconciler = DownloadReconciler(store, scheduler, { 10L },
                checkpointCleaner = DownloadCheckpointCleaner {}, lifecycle = lifecycle)
            val coordinator = DownloadCoordinator(store, scheduler, DownloadNotificationPermissionController {},
                DownloadCheckpointCleaner {}, { 20L }, lifecycle)
            val recovery = async { reconciler.reconcile() }
            entered.await()
            val enqueued = async { coordinator.enqueue(lifecycleRequest("new.gguf")) }
            runCurrent()
            assertFalse(enqueued.isCompleted)
            release.complete(Unit)
            recovery.await()
            val id = enqueued.await()
            assertTrue(id in scheduler.active)
            assertEquals(DownloadUserIntent.RUN, store.getBatch(id)!!.userIntent)
        } finally { database.close() }
    }

    @Test
    fun interruptedCancellationSurvivesReopenAndRetriesFailedCleanup() = runTest {
        val root = Files.createTempDirectory("caraml-cancel-recover").toFile()
        val dbPath = File(root, "downloads.db").path
        val request = lifecycleRequest()
        val metadata = request.artifacts.single().metadata
        val checkpoint = File(root, metadata.destinationRelativePath + ".part")
        checkpoint.parentFile.mkdirs()
        checkpoint.writeBytes(ByteArray(256))
        var database = getDownloadRoomDatabase(getDownloadDatabaseBuilder(dbPath))
        var store = RoomDownloadTaskStore(database.downloadTaskDao())
        val id = store.create(request, 1L)
        val artifact = store.getBatch(id)!!.artifacts.single().artifactId
        assertTrue(store.updateProgress(artifact, 256L, "etag", null, 2L))
        assertTrue(store.setUserIntent(id, DownloadUserIntent.CANCEL, 3L))
        // Simulate a stopped worker persisting CANCELLED before cleanup and process death.
        assertTrue(store.transitionArtifact(artifact, DownloadArtifactState.CANCELLED, null, 4L))
        database.close()
        database = getDownloadRoomDatabase(getDownloadDatabaseBuilder(dbPath))
        store = RoomDownloadTaskStore(database.downloadTaskDao())
        try {
            var attempts = 0
            val cleaner = DownloadCheckpointCleaner {
                attempts++
                if (attempts == 1) error("Storage temporarily unavailable")
                assertTrue(checkpoint.delete() || !checkpoint.exists())
            }
            val reconciler = DownloadReconciler(store, LifecycleScheduler(), { 10L }, checkpointCleaner = cleaner)
            reconciler.reconcile()
            assertTrue(store.getBatch(id)!!.cancellationCleanupPending)
            assertEquals(256L, store.getBatch(id)!!.bytesReceived)
            assertTrue(checkpoint.exists())
            assertTrue(store.observeQueue().first().any { it.batchId == id })
            reconciler.reconcile()
            assertFalse(checkpoint.exists())
            assertFalse(store.getBatch(id)!!.cancellationCleanupPending)
            assertEquals(DownloadBatchState.CANCELLED, store.getBatch(id)!!.state)
            assertEquals(0L, store.getBatch(id)!!.bytesReceived)
            assertNull(store.getBatch(id)!!.artifacts.single().entityTag)
            assertTrue(store.recoverableBatches().isEmpty())
            reconciler.reconcile()
            assertEquals(2, attempts)
        } finally { database.close() }
    }

    @Test
    fun cancellationRacingCompletedPublicationKeepsCompletedBytesAndState() = runTest {
        val database = getDownloadRoomDatabase(getDownloadDatabaseBuilder(
            Files.createTempDirectory("caraml-cancel-completed").resolve("downloads.db").toString()))
        try {
            val store = RoomDownloadTaskStore(database.downloadTaskDao())
            val id = store.create(lifecycleRequest(), 1L)
            val artifact = store.getBatch(id)!!.artifacts.single().artifactId
            assertTrue(store.claim(artifact, "worker", 2L, 50L))
            assertTrue(store.transitionArtifact(artifact, DownloadArtifactState.VERIFYING, null, 3L))
            assertTrue(store.setUserIntent(id, DownloadUserIntent.CANCEL, 4L))
            // Publication already owns the artifact and finishes before the platform stop joins it.
            assertTrue(store.transitionArtifact(artifact, DownloadArtifactState.COMPLETED, null, 5L))
            DownloadReconciler(store, LifecycleScheduler(), { 10L }, checkpointCleaner = DownloadCheckpointCleaner {
                error("Published artifact must not be discarded")
            }).reconcile()
            assertEquals(DownloadBatchState.COMPLETED, store.getBatch(id)!!.state)
            assertEquals(DownloadArtifactState.COMPLETED, store.getBatch(id)!!.artifacts.single().state)
            assertFalse(store.getBatch(id)!!.cancellationCleanupPending)
            assertFalse(store.setUserIntent(id, DownloadUserIntent.CANCEL, 11L))
        } finally { database.close() }
    }

    @Test
    fun allActionableDownloadsRemainVisiblePastRecentFailureLimit() = runTest {
        val database = getDownloadRoomDatabase(getDownloadDatabaseBuilder(
            Files.createTempDirectory("caraml-actionable-queue").resolve("downloads.db").toString()))
        try {
            val store = RoomDownloadTaskStore(database.downloadTaskDao())
            val paused = store.create(lifecycleRequest("paused.gguf"), 1L)
            store.setUserIntent(paused, DownloadUserIntent.PAUSE, 2L)
            store.transitionArtifact(store.getBatch(paused)!!.artifacts.single().artifactId,
                DownloadArtifactState.PAUSED, null, 3L)
            var oldestFailure = ""
            repeat(70) { index ->
                val id = store.create(lifecycleRequest("failed-$index.gguf"), 100L + index)
                if (index == 0) oldestFailure = id
                store.transitionArtifact(store.getBatch(id)!!.artifacts.single().artifactId,
                    DownloadArtifactState.FAILED_TERMINAL, DownloadFailureCode.NETWORK, 200L + index)
            }
            val queue = store.observeQueue().first()
            assertEquals(paused, queue.first().batchId)
            assertEquals(65, queue.size)
            assertFalse(queue.any { it.batchId == oldestFailure })
            val coordinator = DownloadCoordinator(store, LifecycleScheduler(), DownloadNotificationPermissionController {},
                DownloadCheckpointCleaner {}, { 500L })
            assertNotNull(coordinator.getBatch(oldestFailure))
        } finally { database.close() }
    }

    private suspend fun TestScope.withStartupFixture(
        command: SchedulingCommand,
        beforeReconcile: suspend () -> Unit = {},
        block: suspend TestScope.(RoomDownloadTaskStore, LifecycleScheduler, DownloadCoordinator,
            DownloadReconciler, DownloadBatchRequest, String) -> Unit,
    ) {
        val database = getDownloadRoomDatabase(getDownloadDatabaseBuilder(
            Files.createTempDirectory("caraml-startup-command").resolve("downloads.db").toString()))
        try {
            val store = RoomDownloadTaskStore(database.downloadTaskDao())
            val request = lifecycleRequest()
            val id = downloadBatchId(request)
            if (command != SchedulingCommand.ENQUEUE) {
                store.create(request, 1L)
                val artifactId = assertNotNull(store.getBatch(id)).artifacts.single().artifactId
                when (command) {
                    SchedulingCommand.RESUME -> {
                        store.setUserIntent(id, DownloadUserIntent.PAUSE, 2L)
                        store.transitionArtifact(artifactId, DownloadArtifactState.PAUSED, null, 3L)
                    }
                    SchedulingCommand.RETRY -> store.transitionArtifact(artifactId,
                        DownloadArtifactState.FAILED_RETRYABLE, DownloadFailureCode.NETWORK, 3L)
                    SchedulingCommand.ENQUEUE -> error("Unexpected existing enqueue")
                }
            }
            val scheduler = LifecycleScheduler(waitForRestoration = true, beforeReconcile = beforeReconcile)
            val lifecycle = DownloadLifecycleCoordinator()
            val cleaner = DownloadCheckpointCleaner {}
            val coordinator = DownloadCoordinator(store, scheduler, DownloadNotificationPermissionController {},
                cleaner, { 20L }, lifecycle)
            val reconciler = DownloadReconciler(store, scheduler, { 10L }, checkpointCleaner = cleaner, lifecycle = lifecycle)
            block(store, scheduler, coordinator, reconciler, request, id)
        } finally { database.close() }
    }
}

private enum class SchedulingCommand {
    ENQUEUE, RESUME, RETRY;

    suspend fun execute(coordinator: DownloadCoordinator, request: DownloadBatchRequest, id: String) {
        when (this) {
            ENQUEUE -> coordinator.enqueue(request)
            RESUME -> coordinator.resume(id)
            RETRY -> coordinator.retry(id)
        }
    }
}

private fun lifecycleRequest(filename: String = "model.gguf"): DownloadBatchRequest {
    val identity = requireNotNull(DownloadArtifactIdentity.create("owner/model", "a".repeat(40),
        filename, "b".repeat(64), 1024L))
    return DownloadBatchRequest(identity.repositoryId, "text", listOf(DownloadArtifactRequest(
        DownloadMetadataDTO(identity, "model", 1024L, "owner", "gguf", "text-generation"), true)),
        pendingEvidence(identity), false, "Model")
}

private class LifecycleScheduler(
    waitForRestoration: Boolean = false,
    private val beforeReconcile: suspend () -> Unit = {},
) : PlatformDownloadScheduler {
    val active = mutableSetOf<String>()
    val readinessAwaited = CompletableDeferred<Unit>()
    private val restorationReady = CompletableDeferred<Unit>().apply {
        if (!waitForRestoration) complete(Unit)
    }
    var cancellations = 0
    override suspend fun awaitReadyForScheduling() {
        readinessAwaited.complete(Unit)
        restorationReady.await()
    }
    override suspend fun enqueue(batchId: String) {
        awaitReadyForScheduling()
        active += batchId
    }
    override suspend fun isActive(batchId: String): Boolean = batchId in active
    override suspend fun pause(batchId: String) { active -= batchId }
    override suspend fun cancel(batchId: String) { cancellations++; active -= batchId }
    override suspend fun reconcile(liveBatchIds: Set<String>) {
        try {
            beforeReconcile()
            active.retainAll(liveBatchIds)
        } finally {
            restorationReady.complete(Unit)
        }
    }
}
