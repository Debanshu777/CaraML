package com.debanshu777.caraml.core.download

import com.debanshu777.huggingfacemanager.download.DownloadArtifactIdentity
import com.debanshu777.huggingfacemanager.download.DownloadMetadataDTO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadCoordinatorTest {
    @Test
    fun enqueuePersistsBeforePermissionAndScheduling() = runTest {
        val calls = mutableListOf<String>()
        val store = FakeDownloadTaskStore(calls)
        val coordinator = DownloadCoordinator(
            store = store,
            scheduler = RecordingScheduler(calls),
            notifications = DownloadNotificationPermissionController { calls += "permission:request" },
            checkpointCleaner = DownloadCheckpointCleaner {},
            nowEpochMs = { 10L },
        )

        coordinator.enqueue(request())

        assertEquals(listOf("store:create", "permission:request", "scheduler:enqueue"), calls)
    }

    @Test
    fun schedulerFailureLeavesRetryablePersistentRecord() = runTest {
        val calls = mutableListOf<String>()
        val store = FakeDownloadTaskStore(calls)
        val coordinator = DownloadCoordinator(
            store = store,
            scheduler = RecordingScheduler(calls, failEnqueue = true),
            notifications = DownloadNotificationPermissionController {},
            checkpointCleaner = DownloadCheckpointCleaner {},
            nowEpochMs = { 10L },
        )

        coordinator.enqueue(request())

        assertEquals(DownloadArtifactState.FAILED_RETRYABLE, store.snapshot.artifacts.single().state)
        assertEquals(DownloadFailureCode.PLATFORM, store.snapshot.artifacts.single().failureCode)
    }

    @Test
    fun pauseAndResumePersistIntentBeforePlatformCall() = runTest {
        val calls = mutableListOf<String>()
        val store = FakeDownloadTaskStore(calls)
        val scheduler = RecordingScheduler(calls)
        val coordinator = DownloadCoordinator(
            store,
            scheduler,
            DownloadNotificationPermissionController {},
            DownloadCheckpointCleaner {},
            { 10L },
        )

        coordinator.pause("batch")
        coordinator.resume("batch")

        assertEquals(
            listOf("store:intent:PAUSE", "scheduler:pause", "store:intent:RUN", "scheduler:enqueue"),
            calls,
        )
    }

    @Test
    fun cancelStopsPlatformAndDiscardsTheUncommittedCheckpoint() = runTest {
        val calls = mutableListOf<String>()
        val coordinator = DownloadCoordinator(
            FakeDownloadTaskStore(calls),
            RecordingScheduler(calls),
            DownloadNotificationPermissionController {},
            DownloadCheckpointCleaner { calls += "cleaner:discard" },
            { 10L },
        )

        coordinator.cancel("batch")

        assertEquals(
            listOf("store:intent:CANCEL", "scheduler:cancel", "cleaner:discard"),
            calls,
        )
    }

    private fun request(): DownloadBatchRequest {
        val identity = requireNotNull(
            DownloadArtifactIdentity.create("owner/model", "a".repeat(40), "model.gguf", "b".repeat(64), 10L),
        )
        return DownloadBatchRequest(
            ownerModelId = "owner/model",
            modelType = "text",
            artifacts = listOf(
                DownloadArtifactRequest(
                    DownloadMetadataDTO(identity, "model", 10L, null, null, null),
                    primary = true,
                ),
            ),
            evidence = pendingEvidence(identity),
            downloadForLaterConfirmed = false,
            displayName = "Model",
        )
    }
}

private class RecordingScheduler(
    private val calls: MutableList<String>,
    private val failEnqueue: Boolean = false,
) : PlatformDownloadScheduler {
    override suspend fun enqueue(batchId: String) {
        calls += "scheduler:enqueue"
        if (failEnqueue) error("scheduler unavailable")
    }
    override suspend fun pause(batchId: String) { calls += "scheduler:pause" }
    override suspend fun cancel(batchId: String) { calls += "scheduler:cancel" }
    override suspend fun reconcile(liveBatchIds: Set<String>) = Unit
}

private class FakeDownloadTaskStore(
    private val calls: MutableList<String>,
) : DownloadTaskStore {
    private val request = DownloadCoordinatorTestFixture.request
    var snapshot = DownloadBatchSnapshot(
        batchId = "batch",
        ownerModelId = "owner/model",
        modelType = "text",
        displayName = "Model",
        state = DownloadBatchState.QUEUED,
        userIntent = DownloadUserIntent.RUN,
        artifacts = listOf(
            DownloadArtifactSnapshot(
                artifactId = "artifact",
                batchId = "batch",
                request = request.artifacts.single(),
                state = DownloadArtifactState.QUEUED,
                userIntent = DownloadUserIntent.RUN,
                bytesReceived = 0L,
                expectedBytes = 10L,
            ),
        ),
        evidence = request.evidence,
    )

    override suspend fun create(request: DownloadBatchRequest, nowEpochMs: Long): String {
        calls += "store:create"
        return "batch"
    }
    override fun observeForModel(modelId: String): Flow<List<DownloadBatchSnapshot>> = flowOf(listOf(snapshot))
    override suspend fun getBatch(batchId: String): DownloadBatchSnapshot = snapshot
    override suspend fun recoverableBatches(): List<DownloadBatchSnapshot> = listOf(snapshot)
    override suspend fun claim(artifactId: String, owner: String, nowEpochMs: Long, expiresAtEpochMs: Long) = true
    override suspend fun updateProgress(artifactId: String, bytesReceived: Long, entityTag: String?, lastModified: String?, nowEpochMs: Long) = true
    override suspend fun transitionArtifact(artifactId: String, state: DownloadArtifactState, failureCode: DownloadFailureCode?, nowEpochMs: Long): Boolean {
        snapshot = snapshot.copy(
            state = when (state) {
                DownloadArtifactState.FAILED_RETRYABLE -> DownloadBatchState.FAILED_RETRYABLE
                else -> snapshot.state
            },
            artifacts = snapshot.artifacts.map { it.copy(state = state, failureCode = failureCode) },
            failureCode = failureCode,
        )
        return true
    }
    override suspend fun setUserIntent(batchId: String, intent: DownloadUserIntent, nowEpochMs: Long): Boolean {
        calls += "store:intent:$intent"
        snapshot = snapshot.copy(userIntent = intent)
        return true
    }
    override suspend fun setPlatformTaskId(artifactId: String, platformTaskId: String?, nowEpochMs: Long) = true
    override suspend fun releaseLease(artifactId: String, owner: String, nowEpochMs: Long) = true
    override suspend fun clearAll() = Unit
}

private object DownloadCoordinatorTestFixture {
    private val identity = requireNotNull(
        DownloadArtifactIdentity.create("owner/model", "a".repeat(40), "model.gguf", "b".repeat(64), 10L),
    )
    val request = DownloadBatchRequest(
        ownerModelId = "owner/model",
        modelType = "text",
        artifacts = listOf(
            DownloadArtifactRequest(DownloadMetadataDTO(identity, "model", 10L, null, null, null), true),
        ),
        evidence = pendingEvidence(identity),
        downloadForLaterConfirmed = false,
        displayName = "Model",
    )
}
