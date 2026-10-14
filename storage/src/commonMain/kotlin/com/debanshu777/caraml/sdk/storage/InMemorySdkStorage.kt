package com.debanshu777.caraml.sdk.storage

import com.debanshu777.caraml.sdk.api.ArtifactDescriptor
import com.debanshu777.caraml.sdk.api.ArtifactLease
import com.debanshu777.caraml.sdk.api.ArtifactLocation
import com.debanshu777.caraml.sdk.api.ArtifactRef
import com.debanshu777.caraml.sdk.api.ArtifactStore
import com.debanshu777.caraml.sdk.api.DownloadTask
import com.debanshu777.caraml.sdk.api.DownloadTaskState
import com.debanshu777.caraml.sdk.api.DownloadTaskStore
import com.debanshu777.caraml.sdk.api.ExperimentalCaraMLApi
import com.debanshu777.caraml.sdk.api.InstalledModel
import com.debanshu777.caraml.sdk.api.ModelCatalog
import com.debanshu777.caraml.sdk.api.ModelId
import com.debanshu777.caraml.sdk.api.StagedArtifact
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.ByteString.Companion.toByteString

/**
 * Small in-memory implementation for tests, previews, and ephemeral workflows.
 * It is intentionally not a durable store and returns opaque in-memory artifact locations.
 */
@OptIn(ExperimentalCaraMLApi::class)
class InMemorySdkStorage(private val maxArtifactBytes: Long = DEFAULT_MAX_ARTIFACT_BYTES) :
    ModelCatalog,
    ArtifactStore,
    DownloadTaskStore {

    private val mutex = Mutex()
    private val models = linkedMapOf<ModelId, InstalledModel>()
    private val tasks = linkedMapOf<com.debanshu777.caraml.sdk.api.TaskId, DownloadTask>()
    private val staged = linkedMapOf<com.debanshu777.caraml.sdk.api.TaskId, StageState>()
    private val artifacts = linkedMapOf<ArtifactRef, ByteArray>()
    private val leases = mutableMapOf<ArtifactRef, Int>()

    init { require(maxArtifactBytes > 0L) }

    override suspend fun list(): List<InstalledModel> = mutex.withLock { models.values.toList() }

    override suspend fun upsert(model: InstalledModel) {
        mutex.withLock { models[model.descriptor.id] = model.copy(artifacts = model.artifacts.toList()) }
    }

    override suspend fun remove(modelId: ModelId): Boolean = mutex.withLock { models.remove(modelId) != null }

    override suspend fun create(task: DownloadTask): DownloadTask = mutex.withLock {
        require(task.id !in tasks) { "A task with this ID already exists" }
        require(task.state == DownloadTaskState.QUEUED || task.state == DownloadTaskState.PAUSED)
        tasks[task.id] = task.copy(owner = null, generation = 0)
        tasks.getValue(task.id)
    }

    override suspend fun read(id: com.debanshu777.caraml.sdk.api.TaskId): DownloadTask? =
        mutex.withLock { tasks[id] }

    /** Every claim advances generation, fencing any worker that still holds an older token. */
    override suspend fun claim(
        id: com.debanshu777.caraml.sdk.api.TaskId,
        owner: String,
    ): DownloadTask? = mutex.withLock {
        require(owner.isNotBlank())
        val current = tasks[id] ?: return@withLock null
        if (current.state == DownloadTaskState.RUNNING || current.state == DownloadTaskState.SUCCEEDED ||
            current.state == DownloadTaskState.CANCELLED
        ) {
            return@withLock null
        }
        val claimed = current.copy(
            state = DownloadTaskState.RUNNING,
            owner = owner,
            generation = current.generation + 1,
            errorCode = null,
        )
        tasks[id] = claimed
        claimed
    }

    override suspend fun update(task: DownloadTask, owner: String, generation: Long): Boolean = mutex.withLock {
        val current = tasks[task.id] ?: return@withLock false
        if (current.owner != owner || current.generation != generation ||
            task.owner != owner || task.generation != generation ||
            task.bytesReceived < current.bytesReceived ||
            !isAllowedTransition(current.state, task.state)
        ) return@withLock false
        tasks[task.id] = task
        true
    }

    override suspend fun listActive(): List<DownloadTask> = mutex.withLock {
        tasks.values.filter {
            it.state == DownloadTaskState.QUEUED || it.state == DownloadTaskState.RUNNING ||
                it.state == DownloadTaskState.PAUSED
        }
    }

    override suspend fun stage(
        descriptor: ArtifactDescriptor,
        taskId: com.debanshu777.caraml.sdk.api.TaskId,
        owner: String,
        generation: Long,
    ): StagedArtifact = mutex.withLock {
        require(owner.isNotBlank() && generation > 0L)
        val state = staged[taskId]?.also {
            require(it.descriptor == descriptor) { "Staged task was reused for another artifact" }
            require(generation > it.generation) { "Stage generation must advance" }
            it.owner = owner
            it.generation = generation
            it.closed = false
        } ?: StageState(descriptor, owner, generation).also { staged[taskId] = it }
        MemoryStage(descriptor, taskId, owner, generation, state)
    }

    override suspend fun commit(stage: StagedArtifact, owner: String, generation: Long): ArtifactLease = mutex.withLock {
        val memoryStage = requireMemoryStage(stage)
        val state = staged[memoryStage.taskId]
        require(state === memoryStage.state && state.owner == owner && state.generation == generation &&
            owner == memoryStage.owner && generation == memoryStage.generation && !state.closed
        ) { "Stage ownership expired before commit" }
        val bytes = state.snapshot()
        memoryStage.descriptor.sizeBytes?.let { expected ->
            require(bytes.size.toLong() == expected) { "Staged artifact size does not match its descriptor" }
        }
        memoryStage.descriptor.ref.expectedSha256?.let { expected ->
            val actual = bytes.toByteString().sha256().hex()
            require(actual.equals(expected, ignoreCase = true)) { "Staged artifact digest does not match its descriptor" }
        }
        val existing = artifacts[memoryStage.descriptor.ref]
        require(existing == null || existing.contentEquals(bytes)) {
            "A committed artifact cannot be replaced with different content"
        }
        if (existing == null) artifacts[memoryStage.descriptor.ref] = bytes
        staged.remove(memoryStage.taskId)
        state.closed = true
        leaseFor(memoryStage.descriptor.ref)
    }

    override suspend fun abort(stage: StagedArtifact) {
        mutex.withLock {
            val memoryStage = requireMemoryStage(stage)
            val state = staged[memoryStage.taskId]
            if (state === memoryStage.state && state.owner == memoryStage.owner &&
                state.generation == memoryStage.generation
            ) {
                staged.remove(memoryStage.taskId)
                state.closed = true
            }
        }
    }

    override suspend fun acquire(ref: ArtifactRef): ArtifactLease? = mutex.withLock {
        if (ref !in artifacts) null else leaseFor(ref)
    }

    override suspend fun remove(ref: ArtifactRef): Boolean = mutex.withLock {
        if ((leases[ref] ?: 0) > 0) return@withLock false
        artifacts.remove(ref) != null
    }

    /** Test helper for checking committed bytes without exposing mutable backing memory. */
    suspend fun readBytes(ref: ArtifactRef): ByteArray? = mutex.withLock { artifacts[ref]?.copyOf() }

    private fun leaseFor(ref: ArtifactRef): ArtifactLease {
        leases[ref] = (leases[ref] ?: 0) + 1
        return MemoryLease(ref)
    }

    private fun release(ref: ArtifactRef) {
        val count = leases[ref] ?: return
        if (count <= 1) leases.remove(ref) else leases[ref] = count - 1
    }

    private fun requireMemoryStage(stage: StagedArtifact): MemoryStage =
        (stage as? MemoryStage)?.takeIf { it.store === this }
            ?: throw IllegalArgumentException("Staged artifact belongs to another store")

    private fun isAllowedTransition(from: DownloadTaskState, to: DownloadTaskState): Boolean = when (from) {
        DownloadTaskState.QUEUED -> to == DownloadTaskState.RUNNING || to == DownloadTaskState.CANCELLED
        DownloadTaskState.RUNNING -> to == DownloadTaskState.RUNNING || to == DownloadTaskState.PAUSED ||
            to == DownloadTaskState.SUCCEEDED || to == DownloadTaskState.FAILED || to == DownloadTaskState.CANCELLED
        DownloadTaskState.PAUSED -> to == DownloadTaskState.RUNNING || to == DownloadTaskState.CANCELLED
        DownloadTaskState.FAILED -> to == DownloadTaskState.FAILED || to == DownloadTaskState.PAUSED
        DownloadTaskState.SUCCEEDED, DownloadTaskState.CANCELLED -> false
    }

    private inner class MemoryStage(
        override val descriptor: ArtifactDescriptor,
        override val taskId: com.debanshu777.caraml.sdk.api.TaskId,
        override val owner: String,
        override val generation: Long,
        val state: StageState,
    ) : StagedArtifact {
        val store = this@InMemorySdkStorage
        override val bytesWritten: Long get() = state.bytes.size.toLong()

        override suspend fun write(bytes: ByteArray, owner: String, generation: Long) {
            mutex.withLock {
                check(staged[taskId] === state && state.owner == owner && state.generation == generation &&
                    this.owner == owner && this.generation == generation && !state.closed
                ) { "Stage ownership expired before write" }
                require(bytes.size.toLong() <= maxArtifactBytes - state.bytes.size.toLong()) {
                    "In-memory artifact exceeds its configured limit"
                }
                val combined = ByteArray(state.bytes.size + bytes.size)
                state.bytes.copyInto(combined)
                bytes.copyInto(combined, state.bytes.size)
                state.bytes = combined
            }
        }

        override suspend fun close() {
            mutex.withLock {
                if (staged[taskId] === state && state.owner == owner && state.generation == generation) {
                    state.closed = true
                }
            }
        }
    }

    private class StageState(
        val descriptor: ArtifactDescriptor,
        var owner: String,
        var generation: Long,
        var bytes: ByteArray = ByteArray(0),
        var closed: Boolean = false,
    ) {
        fun snapshot() = bytes.copyOf()
    }

    private inner class MemoryLease(override val artifact: ArtifactRef) : ArtifactLease {
        private var closed = false
        override val location: ArtifactLocation = ArtifactLocation.Opaque("memory", artifact.id.value)

        override suspend fun close() {
            mutex.withLock {
                if (closed) return@withLock
                closed = true
                release(artifact)
            }
        }
    }

    companion object { const val DEFAULT_MAX_ARTIFACT_BYTES = 64L * 1024L * 1024L }
}
