package com.debanshu777.caraml.core.media

import kotlinx.coroutines.Dispatchers
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
private val SAFE_FILE_STEM = Regex("[A-Za-z0-9_-]{1,64}")

expect fun generatedMediaCacheDirectory(): String

class GeneratedMediaStore(
    baseDirectory: String = generatedMediaCacheDirectory(),
    sessionId: String = newSessionId(),
    private val maxFileBytes: Long = DEFAULT_MAX_MEDIA_FILE_BYTES,
    private val maxSessionBytes: Long = DEFAULT_MAX_MEDIA_SESSION_BYTES,
) {
    private val fileSystem = FileSystem.SYSTEM
    private val sessionDirectory: Path
    private val gate = Mutex()
    private var storedBytes = 0L

    init {
        require(baseDirectory.isNotBlank()) { "Media cache directory is unavailable" }
        require(SAFE_FILE_STEM.matches(sessionId)) { "Invalid media session identifier" }
        require(maxFileBytes > 0L) { "Invalid media size limit" }
        require(maxSessionBytes > 0L) { "Invalid media session size limit" }
        sessionDirectory = baseDirectory.toPath(normalize = true) / "generated-media-$sessionId"
    }

    suspend fun saveImage(messageId: String, bytes: ByteArray): String =
        withContext(Dispatchers.Default) {
            gate.withLock {
                validateMedia(messageId, bytes)
                fileSystem.createDirectories(sessionDirectory)
                val target = sessionDirectory / "$messageId.png"
                require(fileSystem.metadataOrNull(target) == null) { "Media identifier already exists" }
                requireSessionCapacity(bytes.size.toLong())
                writeAtomically(target, bytes).also {
                    storedBytes += bytes.size.toLong()
                }.toString()
            }
        }

    suspend fun saveVideo(messageId: String, frames: List<ByteArray>): List<String> =
        withContext(Dispatchers.Default) {
            gate.withLock {
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
        gate.withLock {
            val contained = containedExistingPath(path) ?: return@withLock null
            val metadata = fileSystem.metadataOrNull(contained) ?: return@withLock null
            val size = metadata.size ?: return@withLock null
            if (!metadata.isRegularFile || size <= 0L || size > maxFileBytes) return@withLock null
            fileSystem.read(contained) { readByteArray() }
        }
    }

    suspend fun clear() = withContext(Dispatchers.Default) {
        gate.withLock {
            fileSystem.deleteRecursively(sessionDirectory, mustExist = false)
            storedBytes = 0L
        }
    }

    private fun validateMedia(messageId: String, bytes: ByteArray) {
        require(SAFE_FILE_STEM.matches(messageId)) { "Invalid media identifier" }
        require(bytes.isNotEmpty() && bytes.size.toLong() <= maxFileBytes) {
            "Media output is outside the supported range"
        }
    }

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
}
