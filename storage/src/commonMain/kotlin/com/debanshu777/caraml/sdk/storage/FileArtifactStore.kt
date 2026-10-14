package com.debanshu777.caraml.sdk.storage

import com.debanshu777.caraml.sdk.api.ArtifactDescriptor
import com.debanshu777.caraml.sdk.api.ArtifactLease
import com.debanshu777.caraml.sdk.api.ArtifactLocation
import com.debanshu777.caraml.sdk.api.ArtifactRef
import com.debanshu777.caraml.sdk.api.ArtifactStore
import com.debanshu777.caraml.sdk.api.ExperimentalCaraMLApi
import com.debanshu777.caraml.sdk.api.StagedArtifact
import com.debanshu777.caraml.sdk.api.TaskId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.FileSystem
import okio.HashingSource
import okio.Path
import okio.buffer
import okio.ByteString.Companion.encodeUtf8
import okio.use

/**
 * A managed file-backed artifact store serialized within this instance.
 *
 * Construction is I/O-free. The first operation creates directories below [rootDirectory].
 * Artifact and task IDs are hashed before they become path components. Staging metadata and
 * writer fences are process-local: this implementation does not recover orphaned stages after a
 * process restart and does not coordinate with other store instances or processes.
 */
@OptIn(ExperimentalCaraMLApi::class)
class FileArtifactStore(
    private val rootDirectory: Path,
    private val fileSystem: FileSystem,
    private val maxArtifactBytes: Long = DEFAULT_MAX_ARTIFACT_BYTES,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ArtifactStore {
    private val mutex = Mutex()
    private val stagesByTask = mutableMapOf<TaskId, StageState>()
    private val taskByArtifactKey = mutableMapOf<String, TaskId>()
    private val leaseCounts = mutableMapOf<ArtifactRef, Int>()

    init {
        require(rootDirectory.isAbsolute) { "Artifact store root must be an absolute path" }
        require(maxArtifactBytes > 0L)
    }

    override suspend fun stage(
        descriptor: ArtifactDescriptor,
        taskId: TaskId,
        owner: String,
        generation: Long,
    ): StagedArtifact = withContext(ioDispatcher) { mutex.withLock {
        require(owner.isNotBlank() && generation > 0L)
        val artifactKey = artifactKey(descriptor.ref)
        val existingWriter = taskByArtifactKey[artifactKey]
        require(existingWriter == null || existingWriter == taskId) {
            "Another task already has an unfinished stage for this artifact"
        }
        val directories = ensureDirectories()
        val objectPath = directories.objects / "$artifactKey.blob"
        check(fileSystem.metadataOrNull(objectPath) == null) {
            "Artifact is already committed and immutable"
        }

        val state = stagesByTask[taskId]
        val partPath = directories.staging / "${taskKey(taskId)}.part"
        val updatedState = if (state == null) {
            check(fileSystem.metadataOrNull(partPath) == null) {
                "Found an orphaned stage; process-restart reconciliation is unsupported"
            }
            fileSystem.sink(partPath).close()
            StageState(descriptor, taskId, artifactKey, objectPath, partPath, owner, generation)
                .also { stagesByTask[taskId] = it }
        } else {
            require(state.descriptor == descriptor && state.artifactKey == artifactKey) {
                "A staged task cannot be reused for another artifact"
            }
            require(generation > state.generation) { "Stage generation must advance" }
            val partMetadata = fileSystem.metadataOrNull(state.partPath)
            check(partMetadata != null && partMetadata.isRegularFile && partMetadata.symlinkTarget == null) {
                "Staged artifact file is missing or unsafe"
            }
            state.byteCount = partMetadata.size ?: 0L
            state.owner = owner
            state.generation = generation
            state.closed = false
            state
        }
        taskByArtifactKey[artifactKey] = taskId
        FileStage(updatedState)
    } }

    override suspend fun commit(stage: StagedArtifact, owner: String, generation: Long): ArtifactLease {
        var issuedLease: ArtifactLease? = null
        try {
            return withContext(ioDispatcher) { mutex.withLock {
            val fileStage = requireStage(stage)
            val state = fileStage.state
            checkCurrent(state, fileStage.owner, fileStage.generation, owner, generation)
            val partMetadata = fileSystem.metadataOrNull(state.partPath)
            check(partMetadata != null && partMetadata.isRegularFile && partMetadata.symlinkTarget == null) {
                "Staged artifact file is missing or unsafe"
            }
            val size = partMetadata.size ?: error("Staged artifact size is unavailable")
            require(size <= maxArtifactBytes) { "Artifact exceeds the configured storage limit" }
            state.descriptor.sizeBytes?.let { expected ->
                require(size == expected) { "Staged artifact size does not match its descriptor" }
            }
            val digest = sha256(state.partPath)
            state.descriptor.ref.expectedSha256?.let { expected ->
                require(digest.equals(expected, ignoreCase = true)) {
                    "Staged artifact digest does not match its descriptor"
                }
            }

            val existingMetadata = fileSystem.metadataOrNull(state.objectPath)
            if (existingMetadata != null) {
                require(existingMetadata.isRegularFile && existingMetadata.symlinkTarget == null &&
                    existingMetadata.size == size &&
                    sha256(state.objectPath) == digest
                ) { "A committed artifact cannot be replaced with different content" }
                fileSystem.delete(state.partPath)
            } else {
                fileSystem.atomicMove(state.partPath, state.objectPath)
            }
            state.byteCount = size
            state.closed = true
            stagesByTask.remove(state.taskId)
            removeWriter(state.artifactKey, state.taskId)
                leaseFor(state.descriptor.ref, state.objectPath).also { issuedLease = it }
            } }
        } catch (failure: Throwable) {
            withContext(NonCancellable) { issuedLease?.close() }
            throw failure
        }
    }

    override suspend fun abort(stage: StagedArtifact) {
        withContext(ioDispatcher) { mutex.withLock {
            val fileStage = requireStage(stage)
            val state = fileStage.state
            if (ownsStage(state, fileStage.owner, fileStage.generation)) {
                fileSystem.delete(state.partPath)
                state.closed = true
                stagesByTask.remove(state.taskId)
                removeWriter(state.artifactKey, state.taskId)
            }
        } }
    }

    override suspend fun acquire(ref: ArtifactRef): ArtifactLease? {
        var issuedLease: ArtifactLease? = null
        try {
            return withContext(ioDispatcher) { mutex.withLock {
                val objectPath = objectPath(ref)
                val metadata = fileSystem.metadataOrNull(objectPath) ?: return@withLock null
                check(metadata.isRegularFile && metadata.symlinkTarget == null) {
                    "Committed artifact path is not a regular managed file"
                }
                ref.expectedSha256?.let { expected ->
                    check(sha256(objectPath).equals(expected, ignoreCase = true)) {
                        "Committed artifact digest no longer matches its reference"
                    }
                }
                leaseFor(ref, objectPath).also { issuedLease = it }
            } }
        } catch (failure: Throwable) {
            withContext(NonCancellable) { issuedLease?.close() }
            throw failure
        }
    }

    override suspend fun remove(ref: ArtifactRef): Boolean = withContext(ioDispatcher) { mutex.withLock {
        if ((leaseCounts[ref] ?: 0) > 0) return@withLock false
        val key = artifactKey(ref)
        if (taskByArtifactKey.containsKey(key)) return@withLock false
        val path = objectPath(ref)
        if (fileSystem.metadataOrNull(path) == null) return@withLock false
        fileSystem.delete(path)
        true
    } }

    private fun ensureDirectories(): Directories {
        val objects = rootDirectory / "objects"
        val staging = rootDirectory / "staging"
        fileSystem.createDirectories(objects)
        fileSystem.createDirectories(staging)
        for (directory in listOf(objects, staging)) {
            val metadata = fileSystem.metadataOrNull(directory)
            check(metadata != null && metadata.isDirectory && metadata.symlinkTarget == null) {
                "Artifact store directory is missing or unsafe"
            }
        }
        return Directories(objects, staging)
    }

    private fun objectPath(ref: ArtifactRef): Path = rootDirectory / "objects" / "${artifactKey(ref)}.blob"

    private fun artifactKey(ref: ArtifactRef): String = digestKey(
        ref.providerId?.value,
        ref.id.value,
        ref.revision,
        ref.expectedSha256,
    )

    private fun taskKey(taskId: TaskId): String = digestKey(taskId.value)

    private fun digestKey(vararg components: String?): String = components
        .joinToString(separator = "") { value -> value?.let { "${it.length}:$it" } ?: "-:" }
        .encodeUtf8()
        .sha256()
        .hex()

    private suspend fun sha256(path: Path): String {
        val hashingSource = HashingSource.sha256(fileSystem.source(path))
        val buffered = hashingSource.buffer()
        val scratch = Buffer()
        buffered.use {
            while (true) {
                currentCoroutineContext().ensureActive()
                if (it.read(scratch, HASH_CHUNK_BYTES) == -1L) break
                scratch.clear()
            }
        }
        return hashingSource.hash.hex()
    }

    private fun leaseFor(ref: ArtifactRef, path: Path): ArtifactLease {
        leaseCounts[ref] = (leaseCounts[ref] ?: 0) + 1
        return FileLease(ref, path)
    }

    private fun release(ref: ArtifactRef) {
        val count = leaseCounts[ref] ?: return
        if (count <= 1) leaseCounts.remove(ref) else leaseCounts[ref] = count - 1
    }

    private fun removeWriter(artifactKey: String, taskId: TaskId) {
        if (taskByArtifactKey[artifactKey] == taskId) taskByArtifactKey.remove(artifactKey)
    }

    private fun requireStage(stage: StagedArtifact): FileStage =
        (stage as? FileStage)?.takeIf { it.store === this }
            ?: throw IllegalArgumentException("Staged artifact belongs to another store")

    private fun checkCurrent(
        state: StageState,
        stageOwner: String,
        stageGeneration: Long,
        owner: String,
        generation: Long,
    ) {
        check(isCurrent(state, stageOwner, stageGeneration) && owner == stageOwner && generation == stageGeneration) {
            "Stage ownership expired before completion"
        }
    }

    private fun ownsStage(state: StageState, owner: String, generation: Long): Boolean =
        stagesByTask[state.taskId] === state && taskByArtifactKey[state.artifactKey] == state.taskId &&
            state.owner == owner && state.generation == generation

    private fun isCurrent(state: StageState, owner: String, generation: Long): Boolean =
        ownsStage(state, owner, generation) && !state.closed

    private data class Directories(val objects: Path, val staging: Path)

    private inner class FileStage(
        val state: StageState,
    ) : StagedArtifact {
        val store = this@FileArtifactStore
        override val descriptor: ArtifactDescriptor get() = state.descriptor
        override val taskId: TaskId get() = state.taskId
        override val owner: String = state.owner
        override val generation: Long = state.generation
        override val bytesWritten: Long get() = state.byteCount

        override suspend fun write(bytes: ByteArray, owner: String, generation: Long) {
            withContext(ioDispatcher) { mutex.withLock {
                check(isCurrent(state, this@FileStage.owner, this@FileStage.generation) &&
                    owner == this@FileStage.owner && generation == this@FileStage.generation
                ) { "Stage ownership expired before write" }
                val metadata = fileSystem.metadataOrNull(state.partPath)
                check(metadata != null && metadata.isRegularFile && metadata.symlinkTarget == null) {
                    "Staged artifact file is missing or unsafe"
                }
                val currentSize = metadata.size ?: 0L
                require(bytes.size.toLong() <= maxArtifactBytes - currentSize) {
                    "Artifact exceeds the configured storage limit"
                }
                fileSystem.appendingSink(state.partPath).buffer().use { it.write(bytes) }
                state.byteCount = currentSize + bytes.size
            } }
        }

        override suspend fun close() {
            mutex.withLock {
                if (isCurrent(state, owner, generation)) state.closed = true
            }
        }
    }

    private inner class FileLease(
        override val artifact: ArtifactRef,
        private val path: Path,
    ) : ArtifactLease {
        private var closed = false
        override val location: ArtifactLocation = ArtifactLocation.LocalFile(path.toString())

        override suspend fun close() {
            withContext(NonCancellable) {
                mutex.withLock {
                    if (closed) return@withLock
                    closed = true
                    release(artifact)
                }
            }
        }
    }

    private class StageState(
        val descriptor: ArtifactDescriptor,
        val taskId: TaskId,
        val artifactKey: String,
        val objectPath: Path,
        val partPath: Path,
        var owner: String,
        var generation: Long,
        var byteCount: Long = 0L,
        var closed: Boolean = false,
    )

    companion object {
        const val DEFAULT_MAX_ARTIFACT_BYTES: Long = 16L * 1024L * 1024L * 1024L
        private const val HASH_CHUNK_BYTES = 64L * 1024L
    }
}
