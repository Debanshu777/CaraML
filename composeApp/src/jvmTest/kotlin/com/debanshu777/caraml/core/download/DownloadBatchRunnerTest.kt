package com.debanshu777.caraml.core.download

import com.debanshu777.huggingfacemanager.download.DownloadArtifactIdentity
import com.debanshu777.huggingfacemanager.download.DownloadMetadataDTO
import com.debanshu777.huggingfacemanager.download.DownloadProgressDTO
import com.debanshu777.huggingfacemanager.download.DownloadResumeMetadata
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DownloadBatchRunnerTest {
    @Test
    fun persistedCheckpointIsForwardedAndArtifactIsVerifiedBeforeCompletion() = runTest {
        val store = RunnerStore()
        val transfer = RecordingTransfer()
        var finalized = false
        val runner = DownloadBatchRunner(
            store = store,
            transfer = transfer,
            finalizer = BatchFinalizer { finalized = true },
            clock = { 10L },
            leaseOwner = { "test-owner" },
        )

        val result = runner.run("batch") {}

        assertEquals(DownloadResumeMetadata(4L, "etag-1", null), transfer.resume)
        assertEquals(
            listOf(
                DownloadArtifactState.RUNNING,
                DownloadArtifactState.VERIFYING,
                DownloadArtifactState.COMPLETED,
            ),
            store.transitions,
        )
        assertTrue(finalized)
        assertEquals(DownloadRunResult.Completed, result)
        assertTrue(store.released)
    }

    @Test
    fun alreadyPublishedArtifactIsFinalizedWithoutAnotherNetworkTransfer() = runTest {
        val store = RunnerStore()
        val transfer = RecordingTransfer(published = true)
        var finalized = false
        val runner = DownloadBatchRunner(
            store = store,
            transfer = transfer,
            finalizer = BatchFinalizer { finalized = true },
            clock = { 10L },
            leaseOwner = { "recovery-owner" },
        )

        assertEquals(DownloadRunResult.Completed, runner.run("batch") {})

        assertEquals(0, transfer.downloadCalls)
        assertTrue(finalized)
        assertEquals(
            listOf(
                DownloadArtifactState.RUNNING,
                DownloadArtifactState.VERIFYING,
                DownloadArtifactState.COMPLETED,
            ),
            store.transitions,
        )
    }
}

private class RecordingTransfer(
    private val published: Boolean = false,
) : ArtifactTransfer {
    var resume: DownloadResumeMetadata? = null
    var downloadCalls: Int = 0

    override suspend fun isPublished(metadata: DownloadMetadataDTO): Boolean = published

    override fun download(
        metadata: DownloadMetadataDTO,
        resumeMetadata: DownloadResumeMetadata?,
    ): Flow<DownloadProgressDTO> {
        downloadCalls += 1
        resume = resumeMetadata
        return flowOf(
            DownloadProgressDTO(
                bytesReceived = 10L,
                contentLength = 10L,
                percentage = 1f,
                localPath = "/opaque/completed",
                contentSha256 = "c".repeat(64),
                entityTag = "etag-1",
            ),
        )
    }
}

private class RunnerStore : DownloadTaskStore {
    private val identity = requireNotNull(
        DownloadArtifactIdentity.create("owner/model", "a".repeat(40), "model.gguf", "b".repeat(64), 10L),
    )
    private val request = DownloadArtifactRequest(
        DownloadMetadataDTO(identity, "model", 10L, null, null, null),
        primary = true,
    )
    private var batch = DownloadBatchSnapshot(
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
                request = request,
                state = DownloadArtifactState.QUEUED,
                userIntent = DownloadUserIntent.RUN,
                bytesReceived = 4L,
                expectedBytes = 10L,
                entityTag = "etag-1",
            ),
        ),
    )
    val transitions = mutableListOf<DownloadArtifactState>()
    var released = false

    override suspend fun create(request: DownloadBatchRequest, nowEpochMs: Long) = "batch"
    override fun observeForModel(modelId: String) = flowOf(listOf(batch))
    override suspend fun getBatch(batchId: String) = batch
    override suspend fun recoverableBatches() = listOf(batch)
    override suspend fun claim(artifactId: String, owner: String, nowEpochMs: Long, expiresAtEpochMs: Long): Boolean {
        transitions += DownloadArtifactState.RUNNING
        batch = batch.withArtifactState(DownloadArtifactState.RUNNING)
        return true
    }
    override suspend fun updateProgress(artifactId: String, bytesReceived: Long, entityTag: String?, lastModified: String?, nowEpochMs: Long) = true
    override suspend fun transitionArtifact(artifactId: String, state: DownloadArtifactState, failureCode: DownloadFailureCode?, nowEpochMs: Long): Boolean {
        transitions += state
        batch = batch.withArtifactState(state)
        return true
    }
    override suspend fun setUserIntent(batchId: String, intent: DownloadUserIntent, nowEpochMs: Long) = true
    override suspend fun setPlatformTaskId(artifactId: String, platformTaskId: String?, nowEpochMs: Long) = true
    override suspend fun releaseLease(artifactId: String, owner: String, nowEpochMs: Long): Boolean {
        released = true
        return true
    }
    override suspend fun clearAll() = Unit

    private fun DownloadBatchSnapshot.withArtifactState(state: DownloadArtifactState) = copy(
        state = when (state) {
            DownloadArtifactState.RUNNING -> DownloadBatchState.RUNNING
            DownloadArtifactState.VERIFYING -> DownloadBatchState.VERIFYING
            DownloadArtifactState.COMPLETED -> DownloadBatchState.COMPLETED
            else -> this.state
        },
        artifacts = artifacts.map { it.copy(state = state) },
    )
}
