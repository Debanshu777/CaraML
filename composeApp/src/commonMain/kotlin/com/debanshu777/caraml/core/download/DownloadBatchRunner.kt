package com.debanshu777.caraml.core.download

import com.debanshu777.huggingfacemanager.download.ArtifactFileAccessException
import com.debanshu777.huggingfacemanager.download.ArtifactVerificationException
import com.debanshu777.huggingfacemanager.download.DownloadHttpException
import com.debanshu777.huggingfacemanager.download.DownloadManager
import com.debanshu777.huggingfacemanager.download.DownloadMetadataDTO
import com.debanshu777.huggingfacemanager.download.DownloadProgressDTO
import com.debanshu777.huggingfacemanager.download.DownloadResumeMetadata
import com.debanshu777.huggingfacemanager.download.InsufficientStorageException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlin.random.Random

sealed interface DownloadRunResult {
    data object Completed : DownloadRunResult
    data object Paused : DownloadRunResult
    data object Cancelled : DownloadRunResult
    data class Retry(val code: DownloadFailureCode) : DownloadRunResult
    data class Failed(val code: DownloadFailureCode) : DownloadRunResult
}

fun interface BatchFinalizer {
    suspend fun finalize(batchId: String)
}

fun interface ArtifactTransfer {
    fun download(
        metadata: DownloadMetadataDTO,
        resumeMetadata: DownloadResumeMetadata?,
    ): Flow<DownloadProgressDTO>

    suspend fun isPublished(metadata: DownloadMetadataDTO): Boolean = false
}

class DownloadManagerArtifactTransfer(
    private val downloadManager: DownloadManager,
) : ArtifactTransfer {
    override fun download(
        metadata: DownloadMetadataDTO,
        resumeMetadata: DownloadResumeMetadata?,
    ): Flow<DownloadProgressDTO> = downloadManager.download(
        modelId = metadata.artifact.repositoryId,
        path = metadata.artifact.relativePath,
        metadata = metadata,
        resumeMetadata = resumeMetadata,
    )

    override suspend fun isPublished(metadata: DownloadMetadataDTO): Boolean =
        downloadManager.isPublished(metadata)
}

class DownloadBatchRunner(
    private val store: DownloadTaskStore,
    private val transfer: ArtifactTransfer,
    private val finalizer: BatchFinalizer,
    private val clock: () -> Long,
    private val leaseOwner: () -> String = { "runner-${Random.nextLong().toString(16)}" },
) {
    suspend fun run(
        batchId: String,
        progressSink: suspend (DownloadBatchSnapshot) -> Unit,
    ): DownloadRunResult {
        val initial = store.getBatch(batchId) ?: return DownloadRunResult.Failed(DownloadFailureCode.PLATFORM)
        when (initial.userIntent) {
            DownloadUserIntent.PAUSE -> return DownloadRunResult.Paused
            DownloadUserIntent.CANCEL -> return DownloadRunResult.Cancelled
            DownloadUserIntent.RUN -> Unit
        }

        val unscoped = initial.artifacts.filterNot { it.request.metadata.usesImmutableStorageLayout }
        if (unscoped.isNotEmpty()) {
            unscoped.forEach { artifact ->
                if (artifact.state.canTransitionTo(DownloadArtifactState.FAILED_TERMINAL)) {
                    store.transitionArtifact(
                        artifact.artifactId,
                        DownloadArtifactState.FAILED_TERMINAL,
                        DownloadFailureCode.SECURE_PATH,
                        clock(),
                    )
                }
            }
            return DownloadRunResult.Failed(DownloadFailureCode.SECURE_PATH)
        }

        val verifyingArtifacts = mutableListOf<String>()
        for (initialArtifact in initial.artifacts) {
            if (initialArtifact.state == DownloadArtifactState.COMPLETED) continue
            if (initialArtifact.state == DownloadArtifactState.VERIFYING) {
                verifyingArtifacts += initialArtifact.artifactId
                continue
            }
            val isPublished = try {
                transfer.isPublished(initialArtifact.request.metadata)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (isPublished) {
                val owner = leaseOwner().take(128)
                if (store.claim(initialArtifact.artifactId, owner, clock(), clock() + LEASE_DURATION_MS)) {
                    try {
                        store.updateProgress(
                            initialArtifact.artifactId,
                            initialArtifact.expectedBytes,
                            initialArtifact.entityTag,
                            initialArtifact.lastModified,
                            clock(),
                        )
                        store.transitionArtifact(
                            initialArtifact.artifactId,
                            DownloadArtifactState.VERIFYING,
                            null,
                            clock(),
                        )
                        verifyingArtifacts += initialArtifact.artifactId
                    } finally {
                        store.releaseLease(initialArtifact.artifactId, owner, clock())
                    }
                }
                continue
            }
            val owner = leaseOwner().take(128)
            if (!store.claim(initialArtifact.artifactId, owner, clock(), clock() + LEASE_DURATION_MS)) continue
            try {
                val result = runArtifact(batchId, initialArtifact, progressSink)
                if (result != null) return result
                verifyingArtifacts += initialArtifact.artifactId
            } catch (cancelled: CancellationException) {
                checkpointCancellation(batchId, initialArtifact.artifactId)
                throw cancelled
            } catch (stop: StopForIntent) {
                checkpointCancellation(batchId, initialArtifact.artifactId)
                return stop.result
            } catch (error: Exception) {
                return fail(initialArtifact.artifactId, error)
            } finally {
                store.releaseLease(initialArtifact.artifactId, owner, clock())
            }
        }

        return try {
            finalizer.finalize(batchId)
            verifyingArtifacts.forEach { artifactId ->
                store.transitionArtifact(artifactId, DownloadArtifactState.COMPLETED, null, clock())
            }
            store.getBatch(batchId)?.let { progressSink(it) }
            DownloadRunResult.Completed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val code = failureCode(error)
            val terminal = isTerminal(error)
            verifyingArtifacts.forEach { artifactId ->
                store.transitionArtifact(
                    artifactId,
                    if (terminal) DownloadArtifactState.FAILED_TERMINAL else DownloadArtifactState.FAILED_RETRYABLE,
                    code,
                    clock(),
                )
            }
            if (terminal) DownloadRunResult.Failed(code) else DownloadRunResult.Retry(code)
        }
    }

    private suspend fun runArtifact(
        batchId: String,
        artifact: DownloadArtifactSnapshot,
        progressSink: suspend (DownloadBatchSnapshot) -> Unit,
    ): DownloadRunResult? {
        var lastPersistedBytes = artifact.bytesReceived
        var lastPersistedAt = clock()
        val resume = artifact.bytesReceived.takeIf { it > 0L }?.let {
            DownloadResumeMetadata(it, artifact.entityTag, artifact.lastModified)
        }
        transfer.download(artifact.request.metadata, resume).collect { progress ->
            val latest = store.getBatch(batchId) ?: return@collect
            when (latest.userIntent) {
                DownloadUserIntent.PAUSE -> throw StopForIntent(DownloadRunResult.Paused)
                DownloadUserIntent.CANCEL -> throw StopForIntent(DownloadRunResult.Cancelled)
                DownloadUserIntent.RUN -> Unit
            }
            val now = clock()
            if (
                progress.localPath != null ||
                progress.bytesReceived - lastPersistedBytes >= PROGRESS_BYTES_INTERVAL ||
                now - lastPersistedAt >= PROGRESS_TIME_INTERVAL_MS
            ) {
                store.updateProgress(
                    artifact.artifactId,
                    progress.bytesReceived,
                    progress.entityTag,
                    progress.lastModified,
                    now,
                )
                lastPersistedBytes = progress.bytesReceived
                lastPersistedAt = now
                store.getBatch(batchId)?.let { progressSink(it) }
            }
        }
        store.transitionArtifact(artifact.artifactId, DownloadArtifactState.VERIFYING, null, clock())
        store.getBatch(batchId)?.let { progressSink(it) }
        return null
    }

    private suspend fun checkpointCancellation(batchId: String, artifactId: String) {
        val batch = store.getBatch(batchId) ?: return
        when (batch.userIntent) {
            DownloadUserIntent.PAUSE -> store.transitionArtifact(artifactId, DownloadArtifactState.PAUSED, null, clock())
            DownloadUserIntent.CANCEL -> store.transitionArtifact(artifactId, DownloadArtifactState.CANCELLED, null, clock())
            DownloadUserIntent.RUN -> store.transitionArtifact(
                artifactId,
                DownloadArtifactState.FAILED_RETRYABLE,
                DownloadFailureCode.NETWORK,
                clock(),
            )
        }
    }

    private suspend fun fail(artifactId: String, error: Exception): DownloadRunResult {
        val code = failureCode(error)
        val terminal = isTerminal(error)
        store.transitionArtifact(
            artifactId,
            if (terminal) DownloadArtifactState.FAILED_TERMINAL else DownloadArtifactState.FAILED_RETRYABLE,
            code,
            clock(),
        )
        return if (terminal) DownloadRunResult.Failed(code) else DownloadRunResult.Retry(code)
    }

    private fun failureCode(error: Exception): DownloadFailureCode = when (error) {
        is InsufficientStorageException -> DownloadFailureCode.STORAGE
        is ArtifactVerificationException -> DownloadFailureCode.INTEGRITY
        is ArtifactFileAccessException -> DownloadFailureCode.SECURE_PATH
        is DownloadHttpException -> DownloadFailureCode.HTTP
        else -> DownloadFailureCode.NETWORK
    }

    private fun isTerminal(error: Exception): Boolean = when (error) {
        is InsufficientStorageException,
        is ArtifactVerificationException,
        is ArtifactFileAccessException,
        -> true
        is DownloadHttpException -> error.statusCode in 400..499
        else -> false
    }

    private class StopForIntent(val result: DownloadRunResult) : Exception()

    private companion object {
        const val LEASE_DURATION_MS = 15 * 60 * 1000L
        const val PROGRESS_TIME_INTERVAL_MS = 500L
        const val PROGRESS_BYTES_INTERVAL = 1024L * 1024L
    }
}
