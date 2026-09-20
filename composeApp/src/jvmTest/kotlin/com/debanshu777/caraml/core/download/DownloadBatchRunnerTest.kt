package com.debanshu777.caraml.core.download

import com.debanshu777.caraml.core.storage.catalog.InstalledModelPublicationCoordinator
import com.debanshu777.huggingfacemanager.download.DownloadArtifactIdentity
import com.debanshu777.huggingfacemanager.download.DownloadManager
import com.debanshu777.huggingfacemanager.download.DownloadMetadataDTO
import com.debanshu777.huggingfacemanager.download.DownloadProgressDTO
import com.debanshu777.huggingfacemanager.download.DownloadResumeMetadata
import com.debanshu777.huggingfacemanager.download.StoragePathProvider
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun cancellationWhileFinalizerWaitsForOwnerLockNeverMarksArtifactFailed() = runTest {
        val store = RunnerStore(initialArtifactState = DownloadArtifactState.VERIFYING)
        val coordinator = InstalledModelPublicationCoordinator()
        val lockEntered = CompletableDeferred<Unit>()
        val releaseLock = CompletableDeferred<Unit>()
        var finalizerEntered = false
        val lockHolder = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.withOwnerPublication("owner/model") {
                lockEntered.complete(Unit)
                releaseLock.await()
            }
        }
        lockEntered.await()
        val runner = DownloadBatchRunner(
            store = store,
            transfer = RecordingTransfer(),
            finalizer = BatchFinalizer {
                coordinator.withOwnerPublication("owner/model") {
                    finalizerEntered = true
                }
            },
            clock = { 10L },
            leaseOwner = { "cancelled-owner" },
        )

        val runnerJob = async(start = CoroutineStart.UNDISPATCHED) { runner.run("batch") {} }
        runCurrent()
        runnerJob.cancelAndJoin()
        releaseLock.complete(Unit)
        lockHolder.await()

        assertFalse(finalizerEntered)
        assertEquals(emptyList(), store.transitions)
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun cancellationWhilePublishedCheckWaitsForRootLockNeverMutatesQueuedArtifact() = runTest {
        val root = Files.createTempDirectory("caraml-runner-published-check-").toRealPath().toFile()
        val storage = RunnerStoragePathProvider(root)
        val store = RunnerStore()
        val server = BlockingRunnerDownloadServer(ByteArray(10) { it.toByte() })
        val manager = DownloadManager(storage, server.baseUrl)
        val transfer = CountingManifestTransfer(DownloadManagerArtifactTransfer(manager))
        var finalizerCalls = 0
        val lockHolder = async {
            manager.download(
                modelId = store.metadata.artifact.repositoryId,
                path = store.metadata.artifact.relativePath,
                metadata = store.metadata,
            ).collect {}
        }
        try {
            server.requestStarted.await()
            val runner = DownloadBatchRunner(
                store = store,
                transfer = transfer,
                finalizer = BatchFinalizer { finalizerCalls += 1 },
                clock = { 10L },
                leaseOwner = { "cancelled-published-check" },
            )
            val runnerJob = async(start = CoroutineStart.UNDISPATCHED) { runner.run("batch") {} }
            runCurrent()

            runnerJob.cancel(CancellationException("cancelled while reading manifest"))
            assertFailsWith<CancellationException> { runnerJob.await() }

            assertEquals(0, store.claimCalls)
            assertEquals(0, transfer.downloadCalls)
            assertEquals(0, store.progressUpdateCalls)
            assertEquals(emptyList(), store.transitions)
            assertEquals(0, store.releaseCalls)
            assertEquals(0, finalizerCalls)
        } finally {
            server.releaseResponse()
            lockHolder.await()
            server.close()
            root.deleteRecursively()
        }
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

private class CountingManifestTransfer(
    private val delegate: ArtifactTransfer,
) : ArtifactTransfer {
    var downloadCalls: Int = 0

    override suspend fun isPublished(metadata: DownloadMetadataDTO): Boolean =
        delegate.isPublished(metadata)

    override fun download(
        metadata: DownloadMetadataDTO,
        resumeMetadata: DownloadResumeMetadata?,
    ): Flow<DownloadProgressDTO> {
        downloadCalls += 1
        return delegate.download(metadata, resumeMetadata)
    }
}

private class RunnerStore(
    initialArtifactState: DownloadArtifactState = DownloadArtifactState.QUEUED,
) : DownloadTaskStore {
    private val identity = requireNotNull(
        DownloadArtifactIdentity.create("owner/model", "a".repeat(40), "model.gguf", "b".repeat(64), 10L),
    )
    val metadata = DownloadMetadataDTO(identity, "model", 10L, null, null, null)
    private val request = DownloadArtifactRequest(
        metadata,
        primary = true,
    )
    private var batch = DownloadBatchSnapshot(
        batchId = "batch",
        ownerModelId = "owner/model",
        modelType = "text",
        displayName = "Model",
        state = when (initialArtifactState) {
            DownloadArtifactState.VERIFYING -> DownloadBatchState.VERIFYING
            else -> DownloadBatchState.QUEUED
        },
        userIntent = DownloadUserIntent.RUN,
        artifacts = listOf(
            DownloadArtifactSnapshot(
                artifactId = "artifact",
                batchId = "batch",
                request = request,
                state = initialArtifactState,
                userIntent = DownloadUserIntent.RUN,
                bytesReceived = 4L,
                expectedBytes = 10L,
                entityTag = "etag-1",
            ),
        ),
        evidence = pendingEvidence(identity),
    )
    val transitions = mutableListOf<DownloadArtifactState>()
    var released = false
    var claimCalls = 0
    var progressUpdateCalls = 0
    var releaseCalls = 0

    override suspend fun create(request: DownloadBatchRequest, nowEpochMs: Long) = "batch"
    override fun observeForModel(modelId: String) = flowOf(listOf(batch))
    override suspend fun getBatch(batchId: String) = batch
    override suspend fun recoverableBatches() = listOf(batch)
    override suspend fun claim(artifactId: String, owner: String, nowEpochMs: Long, expiresAtEpochMs: Long): Boolean {
        claimCalls += 1
        transitions += DownloadArtifactState.RUNNING
        batch = batch.withArtifactState(DownloadArtifactState.RUNNING)
        return true
    }
    override suspend fun updateProgress(artifactId: String, bytesReceived: Long, entityTag: String?, lastModified: String?, nowEpochMs: Long): Boolean {
        progressUpdateCalls += 1
        return true
    }
    override suspend fun transitionArtifact(artifactId: String, state: DownloadArtifactState, failureCode: DownloadFailureCode?, nowEpochMs: Long): Boolean {
        transitions += state
        batch = batch.withArtifactState(state)
        return true
    }
    override suspend fun setUserIntent(batchId: String, intent: DownloadUserIntent, nowEpochMs: Long) = true
    override suspend fun setPlatformTaskId(artifactId: String, platformTaskId: String?, nowEpochMs: Long) = true
    override suspend fun releaseLease(artifactId: String, owner: String, nowEpochMs: Long): Boolean {
        releaseCalls += 1
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

private class RunnerStoragePathProvider(
    private val root: File,
) : StoragePathProvider {
    override fun getModelsStorageDirectory(modelId: String): String =
        File(File(root, "models").apply { mkdirs() }, modelId).absolutePath

    override fun getDatabasePath(): String = File(root, "caraml.db").absolutePath
    override fun fileExists(path: String): Boolean = File(path).exists()
    override fun getAvailableStorageBytes(): Long = Long.MAX_VALUE
    override fun getTotalStorageBytes(): Long = Long.MAX_VALUE
    override fun isModelFileReadable(path: String): Boolean = File(path).isFile
    override fun isDirectoryReadable(path: String): Boolean = File(path).isDirectory
    override fun getFileSize(path: String): Long = File(path).takeIf { it.exists() }?.length() ?: 0L
    override fun renameFile(from: String, to: String): Boolean = false
    override fun deleteDownloadedModelContent(modelId: String, localPath: String): Boolean = false
}

private class BlockingRunnerDownloadServer(
    private val body: ByteArray,
) : AutoCloseable {
    val requestStarted = CompletableDeferred<Unit>()
    private val responseGate = CountDownLatch(1)
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            requestStarted.complete(Unit)
            responseGate.await()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        start()
    }

    val baseUrl: String = "http://127.0.0.1:${server.address.port}"

    fun releaseResponse() {
        responseGate.countDown()
    }

    override fun close() {
        releaseResponse()
        server.stop(0)
    }
}
