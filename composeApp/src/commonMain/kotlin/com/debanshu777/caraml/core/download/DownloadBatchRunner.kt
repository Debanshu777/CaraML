package com.debanshu777.caraml.core.download

import com.debanshu777.caraml.core.platform.AppLogger
import com.debanshu777.huggingfacemanager.download.downloadTransportFailure
import com.debanshu777.huggingfacemanager.download.ArtifactFileAccessException
import com.debanshu777.huggingfacemanager.download.ArtifactVerificationException
import com.debanshu777.huggingfacemanager.download.DownloadHttpException
import com.debanshu777.huggingfacemanager.download.DownloadManager
import com.debanshu777.huggingfacemanager.download.DownloadMetadataDTO
import com.debanshu777.huggingfacemanager.download.DownloadProgressDTO
import com.debanshu777.huggingfacemanager.download.DownloadResumeMetadata
import com.debanshu777.huggingfacemanager.download.InsufficientStorageException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
            var artifact = initialArtifact
            var publicationKnownMissing = false
            if (artifact.state == DownloadArtifactState.COMPLETED) {
                val stillPublished = try {
                    transfer.isPublished(artifact.request.metadata)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return DownloadRunResult.Retry(DownloadFailureCode.PLATFORM)
                }
                if (stillPublished) continue
                if (!store.requeueMissingCompletedArtifact(artifact.artifactId, clock())) {
                    return DownloadRunResult.Retry(DownloadFailureCode.PLATFORM)
                }
                artifact = artifact.copy(
                    state = DownloadArtifactState.QUEUED,
                    bytesReceived = 0L,
                    entityTag = null,
                    lastModified = null,
                    platformTaskId = null,
                    failureCode = null,
                )
                publicationKnownMissing = true
            }
            if (artifact.state == DownloadArtifactState.VERIFYING) {
                verifyingArtifacts += artifact.artifactId
                continue
            }
            val isPublished = !publicationKnownMissing && publishedOrFalse(artifact.request.metadata)
            if (isPublished) {
                val owner = leaseOwner().take(128)
                try {
                    if (store.claim(artifact.artifactId, owner, clock(), clock() + LEASE_DURATION_MS)) {
                        store.updateProgress(
                            artifact.artifactId,
                            artifact.expectedBytes,
                            artifact.entityTag,
                            artifact.lastModified,
                            clock(),
                        )
                        store.transitionArtifact(
                            artifact.artifactId,
                            DownloadArtifactState.VERIFYING,
                            null,
                            clock(),
                        )
                        verifyingArtifacts += artifact.artifactId
                    }
                } catch (cancelled: CancellationException) {
                    checkpointCancellation(batchId, artifact.artifactId, owner)
                    throw cancelled
                } finally {
                    releaseLease(artifact.artifactId, owner)
                }
                continue
            }
            val owner = leaseOwner().take(128)
            val transferStartedMs = clock()
            try {
                if (!store.claim(artifact.artifactId, owner, clock(), clock() + LEASE_DURATION_MS)) {
                    if (publicationKnownMissing) return DownloadRunResult.Retry(DownloadFailureCode.PLATFORM)
                    continue
                }
                val result = runArtifact(batchId, artifact, owner, progressSink)
                if (result != null) return result
                verifyingArtifacts += artifact.artifactId
            } catch (cancelled: CancellationException) {
                checkpointCancellation(batchId, artifact.artifactId, owner)
                throw cancelled
            } catch (stop: StopForIntent) {
                checkpointCancellation(batchId, artifact.artifactId, owner)
                return stop.result
            } catch (error: Exception) {
                AppLogger.i("Download") {
                    "stage=transfer outcome=failed reason=${downloadTransportFailure(error).name} kinds=${boundedDownloadFailureKinds(error)} httpStatus=${(error as? DownloadHttpException)?.statusCode ?: 0} elapsedMs=${(clock() - transferStartedMs).coerceAtLeast(0L)}"
                }
                return fail(artifact.artifactId, error)
            } finally {
                releaseLease(artifact.artifactId, owner)
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

    private suspend fun publishedOrFalse(metadata: DownloadMetadataDTO): Boolean = try {
        transfer.isPublished(metadata)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private suspend fun runArtifact(
        batchId: String,
        artifact: DownloadArtifactSnapshot,
        owner: String,
        progressSink: suspend (DownloadBatchSnapshot) -> Unit,
    ): DownloadRunResult? {
        var lastPersistedBytes = artifact.bytesReceived
        var lastPersistedAt = clock()
        val resume = DownloadResumeMetadata.createOrNull(artifact.bytesReceived, artifact.entityTag, artifact.lastModified)
        if (resume == null && artifact.bytesReceived > 0L) {
            if (!store.restartTransferCheckpoint(artifact.artifactId, owner, artifact.bytesReceived, clock())) {
                AppLogger.i("Download") { "stage=checkpoint_reset outcome=rejected" }
                checkpointCancellation(batchId, artifact.artifactId, owner)
                return DownloadRunResult.Retry(DownloadFailureCode.PLATFORM)
            }
            lastPersistedBytes = 0L
            AppLogger.i("Download") { "stage=checkpoint_reset outcome=restart" }
        }
        AppLogger.i("Download") {
            "stage=transfer resume=${resume != null} checkpointBytes=${artifact.bytesReceived} hasEntityTag=${resume?.entityTag != null} hasLastModified=${resume?.lastModified != null}"
        }
        transfer.download(artifact.request.metadata, resume).collect { progress ->
            val latest = store.getBatch(batchId) ?: return@collect
            when (latest.userIntent) {
                DownloadUserIntent.PAUSE -> throw StopForIntent(DownloadRunResult.Paused)
                DownloadUserIntent.CANCEL -> throw StopForIntent(DownloadRunResult.Cancelled)
                DownloadUserIntent.RUN -> Unit
            }
            if (progress.bytesReceived < lastPersistedBytes) {
                if (!store.restartTransferCheckpoint(artifact.artifactId, owner, lastPersistedBytes, clock())) {
                    AppLogger.i("Download") { "stage=checkpoint_reset outcome=rejected" }
                    throw StopForIntent(DownloadRunResult.Retry(DownloadFailureCode.PLATFORM))
                }
                lastPersistedBytes = 0L
                AppLogger.i("Download") { "stage=checkpoint_reset outcome=restart" }
            }
            val now = clock()
            if (
                progress.localPath != null ||
                progress.bytesReceived - lastPersistedBytes >= PROGRESS_BYTES_INTERVAL ||
                now - lastPersistedAt >= PROGRESS_TIME_INTERVAL_MS
            ) {
                val ownedProgress = store.updateTransferProgress(
                    artifact.artifactId,
                    owner,
                    progress.bytesReceived,
                    progress.entityTag,
                    progress.lastModified,
                    now,
                    now + LEASE_DURATION_MS,
                )
                if (!ownedProgress) {
                    AppLogger.i("Download") { "stage=lease_renewal outcome=rejected" }
                    throw StopForIntent(DownloadRunResult.Retry(DownloadFailureCode.PLATFORM))
                }
                lastPersistedBytes = progress.bytesReceived
                lastPersistedAt = now
                store.getBatch(batchId)?.let { progressSink(it) }
            }
        }
        store.transitionArtifact(artifact.artifactId, DownloadArtifactState.VERIFYING, null, clock())
        store.getBatch(batchId)?.let { progressSink(it) }
        return null
    }

    private suspend fun checkpointCancellation(batchId: String, artifactId: String, owner: String) {
        withContext(NonCancellable) {
            try {
                withTimeoutOrNull(CLEANUP_TIMEOUT_MS) {
                    store.checkpointCancellation(batchId, artifactId, owner, clock())
                }
            } catch (_: Exception) { }
        }
    }

    private suspend fun releaseLease(artifactId: String, owner: String) {
        withContext(NonCancellable) {
            try {
                withTimeoutOrNull(CLEANUP_TIMEOUT_MS) {
                    store.releaseLease(artifactId, owner, clock())
                }
            } catch (_: Exception) { }
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
        is ReplacementCleanupException -> DownloadFailureCode.PLATFORM
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
        const val CLEANUP_TIMEOUT_MS = 5_000L
    }
}

private fun boundedDownloadFailureKinds(error: Throwable): String {
    val kinds = mutableListOf<String>()
    var cause: Throwable? = error
    repeat(8) {
        val current = cause ?: return kinds.joinToString(",")
        val name = current::class.simpleName
        kinds += name?.takeIf { it.length in 1..64 && it.all { ch -> ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '_' } } ?: "UNKNOWN"
        val next = current.cause
        if (next === current) return kinds.joinToString(",")
        cause = next
    }
    return kinds.joinToString(",")
}
