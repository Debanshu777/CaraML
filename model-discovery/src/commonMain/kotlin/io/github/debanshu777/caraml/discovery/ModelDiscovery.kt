package io.github.debanshu777.caraml.discovery

import io.github.debanshu777.caraml.core.api.ArtifactDescriptor
import io.github.debanshu777.caraml.core.api.ArtifactSource
import io.github.debanshu777.caraml.core.api.ArtifactStore
import io.github.debanshu777.caraml.core.api.DownloadProgress
import io.github.debanshu777.caraml.core.api.DownloadTask
import io.github.debanshu777.caraml.core.api.DownloadTaskState
import io.github.debanshu777.caraml.core.api.DownloadTaskStore
import io.github.debanshu777.caraml.core.api.ExperimentalCaraMLApi
import io.github.debanshu777.caraml.core.api.StagedArtifact
import io.github.debanshu777.caraml.core.api.SuspendCloseable
import io.github.debanshu777.caraml.core.api.ModelCandidate
import io.github.debanshu777.caraml.core.api.ModelPage
import io.github.debanshu777.caraml.core.api.ModelProvider
import io.github.debanshu777.caraml.core.api.ProviderId
import io.github.debanshu777.caraml.core.api.TaskId
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect

/** Optional provider-neutral browse and transfer facade. Building it performs no I/O. */
@OptIn(ExperimentalCaraMLApi::class)
class ModelDiscovery private constructor(
    private val provider: ModelProvider,
    private val downloads: DownloadConfiguration?,
    private val ownedClient: HttpClient?,
    private val ownedProvider: SuspendCloseable?,
) {
    private var closed = false
    private val lifecycleMutex = Mutex()
    private val shutdown = Mutex()
    private var shutdownFailure: Throwable? = null
    private val activeOperations = linkedSetOf<Job>()

    fun search(query: String, pageToken: String? = null): Flow<ModelPage> {
        require(query.length <= MAX_QUERY_LENGTH) { "Search query is too long" }
        return channelFlow {
            inOperation {
                provider.search(query, pageToken).collect { page ->
                    require(page.items.all { it.providerId == provider.id }) {
                        "Provider returned a candidate with a foreign provider identity"
                    }
                    send(page)
                }
            }
        }
    }

    suspend fun resolve(candidate: ModelCandidate): List<ArtifactDescriptor> {
        require(candidate.providerId == provider.id) { "Candidate belongs to another provider" }
        return inOperation {
            provider.resolve(candidate).also { artifacts ->
                require(artifacts.all { it.ref.providerId == null || it.ref.providerId == provider.id }) {
                    "Provider returned an artifact with a foreign provider identity"
                }
            }
        }
    }

    /**
     * Claims a task and copies the provider stream into an artifact staging area.
     * A collection emits a snapshot after each persisted checkpoint. The loop has three total
     * attempts; cancellation is always propagated and leaves the task resumable.
     */
    fun download(taskId: TaskId, owner: String): Flow<DownloadProgress> = channelFlow {
        inOperation {
        val config = downloads ?: error("Downloads were not enabled in the builder")
        require(owner.isNotBlank())
        val claimed = config.taskStore.claim(taskId, owner)
            ?: error("Download task is missing or already terminal")
        val generation = claimed.generation
        var task = claimed.copy(state = DownloadTaskState.RUNNING)
        val source = requireNotNull(config.artifactSource) { "Downloads have no configured artifact source" }
        var stage: StagedArtifact? = null
        var attempt = 0
        var validatorObserved = false
        var transferValidator: String? = null

        try {
            val activeStage = config.artifactStore.stage(task.artifact, taskId, owner, generation)
            stage = activeStage
            while (true) {
                attempt++
                try {
                    source.withTransfer(task.artifact, activeStage.bytesWritten) { transfer ->
                        val descriptorSize = task.artifact.sizeBytes
                        check(transfer.totalBytes == null || descriptorSize == null || transfer.totalBytes == descriptorSize) {
                            "Transfer size does not match the artifact descriptor"
                        }
                        if (validatorObserved) {
                            check(transfer.validator == transferValidator) {
                                "Transfer validator changed while resuming the artifact"
                            }
                        } else {
                            transferValidator = transfer.validator
                            validatorObserved = true
                        }
                        transfer.body.collect { bytes ->
                            if (bytes.isEmpty()) return@collect
                            activeStage.write(bytes, owner, generation)
                            task = task.copy(
                                bytesReceived = activeStage.bytesWritten,
                                totalBytes = transfer.totalBytes ?: task.totalBytes,
                                owner = owner,
                                generation = generation,
                                state = DownloadTaskState.RUNNING,
                            )
                            check(config.taskStore.update(task, owner, generation)) {
                                "Download ownership expired before checkpoint"
                            }
                            send(task.progress())
                        }
                        val expectedTotal = transfer.totalBytes ?: task.artifact.sizeBytes
                        check(expectedTotal == null || activeStage.bytesWritten == expectedTotal) {
                            "Transferred byte count does not match the artifact size"
                        }
                    }
                    break
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (attempt >= MAX_TRANSFER_ATTEMPTS || !failure.isRetryableTransferFailure()) throw failure
                    delay(RETRY_DELAY_MS * attempt)
                }
            }

            val latest = config.taskStore.read(taskId)
            check(latest?.owner == owner && latest.generation == generation && latest.state == DownloadTaskState.RUNNING) {
                "Download ownership expired before artifact publication"
            }
            task = withContext(NonCancellable) {
                val lease = config.artifactStore.commit(activeStage, owner, generation)
                lease.close()
                val completed = task.copy(
                    bytesReceived = activeStage.bytesWritten,
                    state = DownloadTaskState.SUCCEEDED,
                    owner = owner,
                    generation = generation,
                    errorCode = null,
                )
                check(config.taskStore.update(completed, owner, generation)) {
                    "Download ownership expired before completion checkpoint"
                }
                completed
            }
            send(task.progress())
        } catch (cancelled: CancellationException) {
            task = task.copy(
                bytesReceived = stage?.bytesWritten ?: task.bytesReceived,
                state = DownloadTaskState.PAUSED,
                owner = owner,
                generation = generation,
            )
            withContext(NonCancellable) {
                config.taskStore.update(task, owner, generation)
                stage?.close()
            }
            throw cancelled
        } catch (failure: Exception) {
            task = task.copy(
                bytesReceived = stage?.bytesWritten ?: task.bytesReceived,
                state = DownloadTaskState.FAILED,
                owner = owner,
                generation = generation,
                errorCode = failure::class.simpleName ?: "transfer_failed",
            )
            withContext(NonCancellable) {
                config.taskStore.update(task, owner, generation)
                stage?.close()
            }
            throw failure
        }
        }
    }

    /** Cancels this discovery instance's active operations and closes only its owned client. */
    suspend fun close(): Unit = withContext(NonCancellable) {
        shutdown.withLock {
            val jobs = lifecycleMutex.withLock {
                if (closed) {
                    shutdownFailure?.let { throw it }
                    return@withContext
                }
                closed = true
                activeOperations.toList()
            }
            jobs.forEach { it.cancel(CancellationException("ModelDiscovery closed")) }
            jobs.joinAll()
            var cleanupFailure: Throwable? = null
            try { ownedProvider?.close() } catch (failure: Throwable) { cleanupFailure = failure }
            try { ownedClient?.close() } catch (failure: Throwable) {
                if (cleanupFailure == null) cleanupFailure = failure
            }
            shutdownFailure = cleanupFailure
            cleanupFailure?.let { throw it }
        }
    }

    private suspend fun <T> inOperation(block: suspend () -> T): T {
        return coroutineScope {
            val operation = currentCoroutineContext()[kotlinx.coroutines.Job]
                ?: error("Discovery operation has no coroutine job")
            lifecycleMutex.withLock {
                check(!closed) { "ModelDiscovery is closed" }
                activeOperations += operation
            }
            try {
                block()
            } finally {
                withContext(NonCancellable) {
                    lifecycleMutex.withLock { activeOperations -= operation }
                }
            }
        }
    }

    class Builder {
        private var provider: ModelProvider? = null
        private var downloads: DownloadConfiguration? = null
        private var ownedClient: HttpClient? = null
        private var ownedProvider: SuspendCloseable? = null
        private var built = false

        fun provider(provider: ModelProvider) = apply {
            ensureConfigurable()
            require(this.provider == null) { "Choose one provider" }
            this.provider = provider
        }

        /** Configures the built-in Hub provider. The client is borrowed unless [owned] is true. */
        fun huggingFace(
            client: HttpClient,
            token: String? = null,
            baseUrl: String = HuggingFaceProvider.DEFAULT_BASE_URL,
            owned: Boolean = false,
        ) = apply {
            ensureConfigurable()
            require(provider == null) { "Choose one provider" }
            val hubProvider = HuggingFaceProvider(client, token, baseUrl)
            provider = hubProvider
            ownedProvider = hubProvider
            if (owned) ownedClient = client
        }

        fun enableDownloads(
            taskStore: DownloadTaskStore,
            artifactStore: ArtifactStore,
            artifactSource: ArtifactSource? = null,
        ) = apply {
            ensureConfigurable()
            require(downloads == null) { "Downloads are already configured" }
            downloads = DownloadConfiguration(taskStore, artifactStore, artifactSource)
        }

        fun build(): ModelDiscovery {
            ensureConfigurable()
            val selectedProvider = requireNotNull(provider) { "A model provider is required" }
            val selectedDownloads = downloads?.let { config ->
                val source = config.artifactSource ?: selectedProvider as? ArtifactSource
                    ?: error("An artifact source is required for this provider")
                require(source.providerId == selectedProvider.id) {
                    "Artifact source and model provider identities must match"
                }
                config.copy(artifactSource = source)
            }
            built = true
            return ModelDiscovery(selectedProvider, selectedDownloads, ownedClient, ownedProvider)
        }

        private fun ensureConfigurable() { check(!built) { "Builder has already created a client" } }
    }

    private data class DownloadConfiguration(
        val taskStore: DownloadTaskStore,
        val artifactStore: ArtifactStore,
        val artifactSource: ArtifactSource?,
    )

    private fun DownloadTask.progress() = DownloadProgress(id, bytesReceived, totalBytes, state)

    private fun Exception.isRetryableTransferFailure(): Boolean = when (this) {
        is HubProtocolException -> false
        is HubHttpException -> statusCode == 408 || statusCode == 429 || statusCode in 500..599
        is IOException, is HttpRequestTimeoutException -> true
        else -> false
    }

    companion object {
        private const val MAX_QUERY_LENGTH = 512
        private const val MAX_TRANSFER_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 250L
    }
}
