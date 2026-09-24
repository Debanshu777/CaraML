package com.debanshu777.caraml.core.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.random.Random
import kotlin.time.Clock

private const val DEFAULT_MAX_MEDIA_FILE_BYTES = 96L * 1024L * 1024L
private const val DEFAULT_MAX_MEDIA_SESSION_BYTES = 512L * 1024L * 1024L
private const val DEFAULT_MAX_MEDIA_GLOBAL_BYTES = 1024L * 1024L * 1024L
private const val DEFAULT_MAX_ABANDONED_AGE_MILLIS = 24L * 60L * 60L * 1_000L
private const val MAX_ABANDONED_SESSION_COUNT = 256
private const val MAX_ACTIVE_SESSION_COUNT = 256
private const val MAX_SESSION_ENTRY_COUNT = 4_096
private const val SESSION_DIRECTORY_PREFIX = "generated-media-"
private val SAFE_FILE_STEM = Regex("[A-Za-z0-9_-]{1,64}")
private val SAFE_SESSION_DIRECTORY = Regex("generated-media-[A-Za-z0-9_-]{1,64}")

private object GeneratedMediaProcessCoordinator {
    val gate = Mutex()

    private data class RootState(
        val maxGlobalBytes: Long,
        val activeSessions: MutableSet<Path> = mutableSetOf(),
    )

    private val roots = mutableMapOf<Path, RootState>()

    fun register(
        canonicalBase: Path,
        sessionDirectory: Path,
        maxGlobalBytes: Long,
    ) {
        val state = roots.getOrPut(canonicalBase) { RootState(maxGlobalBytes) }
        require(state.maxGlobalBytes == maxGlobalBytes) {
            "Media stores sharing a cache root must use the same global limit"
        }
        require(state.activeSessions.size < MAX_ACTIVE_SESSION_COUNT) {
            "Too many active media sessions"
        }
        require(state.activeSessions.add(sessionDirectory)) {
            "Media session is already active"
        }
    }

    fun unregister(canonicalBase: Path, sessionDirectory: Path) {
        val state = roots[canonicalBase] ?: return
        state.activeSessions.remove(sessionDirectory)
        if (state.activeSessions.isEmpty()) roots.remove(canonicalBase)
    }

    fun activeSessions(canonicalBase: Path): Set<Path> =
        roots[canonicalBase]?.activeSessions?.toSet().orEmpty()

    fun globalLimit(canonicalBase: Path): Long =
        requireNotNull(roots[canonicalBase]).maxGlobalBytes
}

expect fun generatedMediaCacheDirectory(): String

class GeneratedMediaStore(
    baseDirectory: String = generatedMediaCacheDirectory(),
    sessionId: String = newSessionId(),
    private val maxFileBytes: Long = DEFAULT_MAX_MEDIA_FILE_BYTES,
    private val maxSessionBytes: Long = DEFAULT_MAX_MEDIA_SESSION_BYTES,
    private val maxGlobalBytes: Long = DEFAULT_MAX_MEDIA_GLOBAL_BYTES,
    private val maxAbandonedAgeMillis: Long = DEFAULT_MAX_ABANDONED_AGE_MILLIS,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val fileSystem = FileSystem.SYSTEM
    private val baseDirectory: Path
    private val sessionDirectory: Path
    private var prepared = false
    private var storedBytes = 0L
    private var registeredBase: Path? = null

    init {
        require(baseDirectory.isNotBlank()) { "Media cache directory is unavailable" }
        require(SAFE_FILE_STEM.matches(sessionId)) { "Invalid media session identifier" }
        require(maxFileBytes > 0L) { "Invalid media size limit" }
        require(maxSessionBytes > 0L) { "Invalid media session size limit" }
        require(maxGlobalBytes >= maxSessionBytes) { "Invalid media global size limit" }
        require(maxAbandonedAgeMillis >= 0L) { "Invalid abandoned media age" }
        this.baseDirectory = baseDirectory.toPath(normalize = true)
        sessionDirectory = this.baseDirectory / "$SESSION_DIRECTORY_PREFIX$sessionId"
    }

    suspend fun prepare() = withContext(Dispatchers.Default) {
        GeneratedMediaProcessCoordinator.gate.withLock { prepareLocked() }
    }

    suspend fun saveImage(messageId: String, bytes: ByteArray): String =
        withContext(Dispatchers.Default) {
            GeneratedMediaProcessCoordinator.gate.withLock {
                prepareLocked()
                validateMedia(messageId, bytes)
                fileSystem.createDirectories(sessionDirectory)
                val target = sessionDirectory / "$messageId.png"
                require(fileSystem.metadataOrNull(target) == null) { "Media identifier already exists" }
                requireSessionCapacity(bytes.size.toLong())
                ensureGlobalCapacity(bytes.size.toLong())
                writeAtomically(target, bytes).also {
                    storedBytes += bytes.size.toLong()
                }.toString()
            }
        }

    suspend fun saveVideo(messageId: String, frames: List<ByteArray>): List<String> =
        withContext(Dispatchers.Default) {
            GeneratedMediaProcessCoordinator.gate.withLock {
                prepareLocked()
                require(SAFE_FILE_STEM.matches(messageId)) { "Invalid media identifier" }
                require(frames.isNotEmpty()) { "Video output is empty" }
                frames.forEach { validateMedia(messageId, it) }
                val batchBytes = frames.fold(0L) { total, frame ->
                    val frameBytes = frame.size.toLong()
                    require(total <= Long.MAX_VALUE - frameBytes) {
                        "Media output is outside the supported range"
                    }
                    total + frameBytes
                }

                val videoDirectory = sessionDirectory / messageId
                fileSystem.createDirectories(sessionDirectory)
                require(fileSystem.metadataOrNull(videoDirectory) == null) {
                    "Media identifier already exists"
                }
                requireSessionCapacity(batchBytes)
                ensureGlobalCapacity(batchBytes)
                fileSystem.createDirectories(videoDirectory)
                try {
                    frames.mapIndexed { index, bytes ->
                        writeAtomically(
                            videoDirectory / "frame-${index.toString().padStart(4, '0')}.png",
                            bytes,
                        ).toString()
                    }.also { storedBytes += batchBytes }
                } catch (error: Throwable) {
                    fileSystem.deleteRecursively(videoDirectory, mustExist = false)
                    throw error
                }
            }
        }

    suspend fun read(path: String): ByteArray? = withContext(Dispatchers.Default) {
        GeneratedMediaProcessCoordinator.gate.withLock {
            prepareLocked()
            val contained = containedExistingPath(path) ?: return@withLock null
            val metadata = fileSystem.metadataOrNull(contained) ?: return@withLock null
            val size = metadata.size ?: return@withLock null
            if (!metadata.isRegularFile || size <= 0L || size > maxFileBytes) return@withLock null
            fileSystem.read(contained) { readByteArray() }
        }
    }

    suspend fun clear() = withContext(Dispatchers.Default) {
        GeneratedMediaProcessCoordinator.gate.withLock {
            val canonicalBase = registeredBase ?: return@withLock
            try {
                fileSystem.deleteRecursively(sessionDirectory, mustExist = false)
            } finally {
                GeneratedMediaProcessCoordinator.unregister(canonicalBase, sessionDirectory)
                registeredBase = null
                prepared = false
                storedBytes = 0L
            }
        }
    }

    private fun validateMedia(messageId: String, bytes: ByteArray) {
        require(SAFE_FILE_STEM.matches(messageId)) { "Invalid media identifier" }
        require(bytes.isNotEmpty() && bytes.size.toLong() <= maxFileBytes) {
            "Media output is outside the supported range"
        }
    }

    private suspend fun prepareLocked() {
        if (prepared) return
        fileSystem.createDirectories(baseDirectory)
        val baseMetadata = requireNotNull(fileSystem.metadataOrNull(baseDirectory)) {
            "Media cache directory is unavailable"
        }
        require(baseMetadata.isDirectory && baseMetadata.symlinkTarget == null) {
            "Media cache directory is unsafe"
        }
        val canonicalBase = fileSystem.canonicalize(baseDirectory)
        val activeMetadata = fileSystem.metadataOrNull(sessionDirectory)
        require(
            activeMetadata == null ||
                activeMetadata.isDirectory && activeMetadata.symlinkTarget == null &&
                isDirectContainedDirectory(sessionDirectory, canonicalBase)
        ) { "Media session directory is unsafe" }
        GeneratedMediaProcessCoordinator.register(canonicalBase, sessionDirectory, maxGlobalBytes)
        registeredBase = canonicalBase
        try {
            storedBytes = activeMetadata?.let {
                inspectSession(sessionDirectory, canonicalBase).bytes
            } ?: 0L
            require(storedBytes <= maxSessionBytes) { "Generated media cache is full" }
            pruneAbandonedSessions(
                canonicalBase = canonicalBase,
                requiredFreeBytes = maxSessionBytes - storedBytes,
            )
            prepared = true
        } catch (error: Throwable) {
            GeneratedMediaProcessCoordinator.unregister(canonicalBase, sessionDirectory)
            registeredBase = null
            throw error
        }
    }

    private suspend fun pruneAbandonedSessions(
        canonicalBase: Path,
        requiredFreeBytes: Long,
    ) {
        val activeSessions = GeneratedMediaProcessCoordinator.activeSessions(canonicalBase)
        val candidates = fileSystem.listOrNull(baseDirectory)
            .orEmpty()
            .filter { candidate ->
                candidate.parent == baseDirectory &&
                    SAFE_SESSION_DIRECTORY.matches(candidate.name)
            }
        val abandonedCandidates = candidates.filterNot(activeSessions::contains)
        val abandonedSlots = (MAX_ABANDONED_SESSION_COUNT - activeSessions.size).coerceAtLeast(0)

        abandonedCandidates.drop(abandonedSlots).forEach { candidate ->
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            deleteContainedSession(candidate, canonicalBase)
        }

        val retainedCandidates = candidates.filter(activeSessions::contains) +
            abandonedCandidates.take(abandonedSlots)
        val sessions = retainedCandidates.mapNotNull { candidate ->
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val metadata = runCatching { fileSystem.metadataOrNull(candidate) }.getOrNull()
                ?: return@mapNotNull null
            if (metadata.symlinkTarget != null) {
                require(candidate !in activeSessions) { "Media session directory is unsafe" }
                runCatching { fileSystem.delete(candidate, mustExist = false) }
                return@mapNotNull null
            }
            if (!metadata.isDirectory || !isDirectContainedDirectory(candidate, canonicalBase)) {
                return@mapNotNull null
            }
            runCatching { inspectSession(candidate, canonicalBase) }.getOrNull()
        }

        val now = clock()
        var activeBytes = 0L
        val retained = mutableListOf<SessionUsage>()
        sessions.forEach { session ->
            if (session.path in activeSessions) {
                activeBytes = saturatedAdd(activeBytes, session.bytes)
                return@forEach
            }
            val expired = maxAbandonedAgeMillis != Long.MAX_VALUE &&
                session.lastModifiedAtMillis <= now &&
                now - session.lastModifiedAtMillis >= maxAbandonedAgeMillis
            if (!expired || !deleteContainedSession(session.path, canonicalBase)) {
                retained += session
            }
        }

        retained.sortBy(SessionUsage::lastModifiedAtMillis)
        val globalLimit = GeneratedMediaProcessCoordinator.globalLimit(canonicalBase)
        require(requiredFreeBytes in 0..globalLimit) { "Generated media cache is full" }
        val existingByteLimit = globalLimit - requiredFreeBytes
        var retainedBytes = retained.fold(0L) { total, session -> saturatedAdd(total, session.bytes) }
        for (session in retained) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (saturatedAdd(activeBytes, retainedBytes) <= existingByteLimit) break
            if (deleteContainedSession(session.path, canonicalBase)) {
                retainedBytes = (retainedBytes - session.bytes).coerceAtLeast(0L)
            }
        }
        require(saturatedAdd(activeBytes, retainedBytes) <= existingByteLimit) {
            "Generated media cache is full"
        }
    }

    private suspend fun ensureGlobalCapacity(additionalBytes: Long) {
        val canonicalBase = requireNotNull(registeredBase)
        pruneAbandonedSessions(canonicalBase, additionalBytes)
    }

    private fun inspectSession(path: Path, canonicalBase: Path): SessionUsage {
        require(isDirectContainedDirectory(path, canonicalBase)) { "Media session directory is unsafe" }
        val rootMetadata = requireNotNull(fileSystem.metadataOrNull(path))
        require(rootMetadata.isDirectory && rootMetadata.symlinkTarget == null) {
            "Media session directory is unsafe"
        }
        var bytes = 0L
        val lastModified = rootMetadata.lastModifiedAtMillis ?: rootMetadata.createdAtMillis ?: 0L
        var entries = 0
        for (entry in fileSystem.listRecursively(path, false)) {
            entries += 1
            if (entries > MAX_SESSION_ENTRY_COUNT) {
                return SessionUsage(path, maxGlobalBytes, lastModified)
            }
            val metadata = fileSystem.metadataOrNull(entry) ?: continue
            if (metadata.isRegularFile && metadata.symlinkTarget == null) {
                bytes = saturatedAdd(bytes, metadata.size ?: 0L)
            }
        }
        return SessionUsage(path, bytes, lastModified)
    }

    private fun deleteContainedSession(path: Path, canonicalBase: Path): Boolean {
        if (path.parent != baseDirectory || !SAFE_SESSION_DIRECTORY.matches(path.name)) return false
        if (path in GeneratedMediaProcessCoordinator.activeSessions(canonicalBase)) return false
        val metadata = runCatching { fileSystem.metadataOrNull(path) }.getOrNull() ?: return true
        return runCatching {
            if (metadata.symlinkTarget != null) {
                fileSystem.delete(path, mustExist = false)
            } else {
                require(metadata.isDirectory && isDirectContainedDirectory(path, canonicalBase))
                fileSystem.deleteRecursively(path, mustExist = false)
            }
            true
        }.getOrDefault(false)
    }

    private fun isDirectContainedDirectory(path: Path, canonicalBase: Path): Boolean =
        runCatching { fileSystem.canonicalize(path).parent == canonicalBase }.getOrDefault(false)

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right

    private fun requireSessionCapacity(additionalBytes: Long) {
        require(
            additionalBytes in 1..maxSessionBytes &&
                storedBytes <= maxSessionBytes - additionalBytes
        ) { "Generated media cache is full" }
    }

    private fun writeAtomically(target: Path, bytes: ByteArray): Path {
        val temporary = target.parent!! / ".${target.name}.part"
        fileSystem.delete(temporary, mustExist = false)
        try {
            fileSystem.write(temporary) { write(bytes) }
            fileSystem.atomicMove(temporary, target)
            return target
        } catch (error: Throwable) {
            fileSystem.delete(temporary, mustExist = false)
            throw error
        }
    }

    private fun containedExistingPath(path: String): Path? {
        if (path.isBlank()) return null
        if (fileSystem.metadataOrNull(sessionDirectory)?.isDirectory != true) return null
        val root = runCatching { fileSystem.canonicalize(sessionDirectory) }.getOrNull() ?: return null
        val candidate = runCatching { fileSystem.canonicalize(path.toPath(normalize = true)) }
            .getOrNull() ?: return null
        if (root.root != candidate.root || candidate.segments.size <= root.segments.size) return null
        return candidate.takeIf {
            candidate.segments.take(root.segments.size) == root.segments
        }
    }

    companion object {
        private fun newSessionId(): String {
            val random = Random.nextInt().toUInt().toString(16)
            return "${Clock.System.now().toEpochMilliseconds()}-$random"
        }
    }

    private data class SessionUsage(
        val path: Path,
        val bytes: Long,
        val lastModifiedAtMillis: Long,
    )
}
