@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.debanshu777.huggingfacemanager.download

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import okio.Buffer
import okio.HashingSource
import okio.Path
import okio.Sink
import okio.Source
import okio.Timeout
import platform.posix.AT_REMOVEDIR
import platform.posix.EEXIST
import platform.posix.ENOENT
import platform.posix.F_DUPFD_CLOEXEC
import platform.posix.O_CLOEXEC
import platform.posix.O_CREAT
import platform.posix.O_DIRECTORY
import platform.posix.O_EXCL
import platform.posix.O_NOFOLLOW
import platform.posix.O_NONBLOCK
import platform.posix.O_RDONLY
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.close
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.fstat
import platform.posix.fsync
import platform.posix.mkdirat
import platform.posix.open
import platform.posix.openat
import platform.posix.read
import platform.posix.renameat
import platform.posix.stat
import platform.posix.unlinkat
import platform.posix.write

internal actual class SecureArtifactRoot actual constructor(modelRoot: Path) {
    private val rootPath = modelRoot.normalized().toString().trimEnd('/')
    private val pinnedRoot = openPinnedRoot(rootPath)
    private var rootDescriptor: Int = pinnedRoot.descriptor
    private val rootDevice: ULong = pinnedRoot.device
    private val rootInode: ULong = pinnedRoot.inode

    actual companion object {
        actual fun create(modelsRoot: Path, modelId: String): SecureArtifactRoot =
            SecureArtifactRoot(modelsRoot / validateModelId(modelId))
    }

    actual fun createParentDirectories(relativePath: String) {
        verifyRootIdentity()
        val segments = validatedSegments(relativePath).dropLast(1)
        var current = duplicateRoot()
        try {
            segments.forEach { segment ->
                val created = mkdirat(current, segment, 448u) == 0
                if (!created && errno != EEXIST) throw ArtifactFileAccessException()
                if (created && fsync(current) != 0) throw ArtifactFileAccessException()
                val next = openat(current, segment, O_RDONLY or O_DIRECTORY or O_NOFOLLOW or O_CLOEXEC)
                if (next < 0) throw ArtifactFileAccessException()
                close(current)
                current = next
            }
        } finally {
            close(current)
            verifyRootIdentity()
        }
    }

    actual fun sink(relativePath: String, mustCreate: Boolean): Sink {
        val opened = openFile(
            relativePath,
            O_WRONLY or O_CLOEXEC or O_NOFOLLOW or if (mustCreate) O_CREAT or O_EXCL else O_CREAT or O_TRUNC,
            createMode = 384u,
        )
        return DescriptorSink(opened)
    }

    actual fun existsRegularFile(relativePath: String): Boolean {
        val descriptor = runCatching { openFile(relativePath, O_RDONLY or O_CLOEXEC or O_NOFOLLOW) }.getOrNull()
            ?: return false
        return try {
            descriptorSizeIfRegular(descriptor) != null
        } finally {
            close(descriptor)
        }
    }

    actual fun size(relativePath: String): Long? {
        val descriptor = runCatching { openFile(relativePath, O_RDONLY or O_CLOEXEC or O_NOFOLLOW) }.getOrNull()
            ?: return null
        return try {
            descriptorSizeIfRegular(descriptor)
        } finally {
            close(descriptor)
        }
    }

    actual fun readBounded(relativePath: String, maxBytes: Int): ByteArray? {
        val descriptor = runCatching { openFile(relativePath, O_RDONLY or O_CLOEXEC or O_NOFOLLOW) }.getOrNull()
            ?: return null
        return try {
            val source = DescriptorSource(descriptor, ownsDescriptor = false)
            val buffer = Buffer()
            val limit = maxBytes.toLong() + 1L
            while (buffer.size < limit) {
                val count = source.read(buffer, minOf(8_192L, limit - buffer.size))
                if (count == -1L) break
            }
            buffer.readByteArray().takeIf { it.size in 1..maxBytes }
        } catch (_: Exception) {
            null
        } finally {
            close(descriptor)
        }
    }

    actual fun sha256(relativePath: String, expectedBytes: Long): String? {
        val descriptor = runCatching { openFile(relativePath, O_RDONLY or O_CLOEXEC or O_NOFOLLOW) }.getOrNull()
            ?: return null
        return try {
            if (descriptorSizeIfRegular(descriptor) != expectedBytes) return null
            val hashing = HashingSource.sha256(DescriptorSource(descriptor, ownsDescriptor = false))
            val buffer = Buffer()
            var total = 0L
            while (total <= expectedBytes) {
                val count = hashing.read(buffer, minOf(256L * 1024L, expectedBytes + 1L - total))
                if (count == -1L) break
                total += count
                buffer.clear()
            }
            if (total == expectedBytes) hashing.hash.hex() else null
        } catch (_: Exception) {
            null
        } finally {
            close(descriptor)
        }
    }

    actual fun atomicMove(sourceRelativePath: String, targetRelativePath: String) {
        withParent(sourceRelativePath) { sourceParent, sourceName ->
            withParent(targetRelativePath) { targetParent, targetName ->
                if (renameat(sourceParent, sourceName, targetParent, targetName) != 0) {
                    throw ArtifactFileAccessException()
                }
            }
        }
    }

    actual fun delete(relativePath: String) {
        withParent(relativePath) { parent, name ->
            if (unlinkat(parent, name, 0) != 0 && errno != ENOENT) throw ArtifactFileAccessException()
        }
    }

    actual fun syncFile(relativePath: String) {
        val descriptor = openFile(relativePath, O_RDONLY or O_CLOEXEC or O_NOFOLLOW)
        try {
            if (fcntl(descriptor, DARWIN_F_FULLFSYNC) != 0) throw ArtifactFileAccessException()
        } finally {
            close(descriptor)
        }
    }

    actual fun syncDirectory(relativePath: String) {
        val descriptor = if (relativePath == ".") duplicateRoot() else openDirectory(relativePath)
        try {
            if (fsync(descriptor) != 0) throw ArtifactFileAccessException()
        } finally {
            close(descriptor)
            verifyRootIdentity()
        }
    }

    actual fun revalidate() = verifyRootIdentity()

    actual fun close() {
        val descriptor = rootDescriptor
        rootDescriptor = -1
        if (descriptor >= 0) close(descriptor)
    }

    private fun openDirectory(relativePath: String): Int {
        val segments = validatedSegments(relativePath)
        var current = duplicateRoot()
        try {
            segments.forEach { segment ->
                val next = openat(current, segment, O_RDONLY or O_DIRECTORY or O_NOFOLLOW or O_CLOEXEC)
                if (next < 0) throw ArtifactFileAccessException()
                close(current)
                current = next
            }
            return current.also { current = -1 }
        } finally {
            if (current >= 0) close(current)
        }
    }

    private fun openFile(relativePath: String, flags: Int, createMode: UInt = 0u): Int =
        withParent(relativePath) { parent, name ->
            val safeFlags = flags or O_NONBLOCK or O_NOFOLLOW or O_CLOEXEC
            val descriptor = if (flags and O_CREAT != 0) {
                openat(parent, name, safeFlags, createMode)
            } else {
                openat(parent, name, safeFlags)
            }
            if (descriptor < 0) throw ArtifactFileAccessException()
            if (descriptorSizeIfRegular(descriptor) == null) {
                close(descriptor)
                throw ArtifactFileAccessException()
            }
            descriptor
        }

    private inline fun <T> withParent(relativePath: String, block: (Int, String) -> T): T {
        verifyRootIdentity()
        val segments = validatedSegments(relativePath)
        var current = duplicateRoot()
        try {
            segments.dropLast(1).forEach { segment ->
                val next = openat(current, segment, O_RDONLY or O_DIRECTORY or O_NOFOLLOW or O_CLOEXEC)
                if (next < 0) throw ArtifactFileAccessException()
                close(current)
                current = next
            }
            return block(current, segments.last())
        } finally {
            close(current)
            verifyRootIdentity()
        }
    }

    private fun duplicateRoot(): Int {
        if (rootDescriptor < 0) throw ArtifactFileAccessException()
        return fcntl(rootDescriptor, F_DUPFD_CLOEXEC, 0).also {
            if (it < 0) throw ArtifactFileAccessException()
        }
    }

    private fun validatedSegments(relativePath: String): List<String> {
        val validated = runCatching { validateDownloadRequest("owner/model", relativePath).relativePath }
            .getOrNull() ?: throw ArtifactFileAccessException()
        if (validated != relativePath) throw ArtifactFileAccessException()
        return validated.split('/')
    }

    private fun verifyRootIdentity() {
        val reopened = openPinnedRoot(rootPath, create = false)
        try {
            if (reopened.device != rootDevice || reopened.inode != rootInode) throw ArtifactFileAccessException()
        } finally {
            close(reopened.descriptor)
        }
    }

    private fun descriptorSizeIfRegular(descriptor: Int): Long? = memScoped {
        val metadata = alloc<stat>()
        if (fstat(descriptor, metadata.ptr) != 0) return@memScoped null
        if (metadata.st_mode.toInt() and S_IFMT != S_IFREG) return@memScoped null
        metadata.st_size
    }
}

private data class PinnedRoot(val descriptor: Int, val device: ULong, val inode: ULong)

private fun openPinnedRoot(modelRoot: String, create: Boolean = true): PinnedRoot {
    if (!modelRoot.startsWith('/') || modelRoot.length > 4096) throw ArtifactFileAccessException()
    val modelName = modelRoot.substringAfterLast('/')
    val ownerPath = modelRoot.substringBeforeLast('/', "")
    val ownerName = ownerPath.substringAfterLast('/')
    val modelsRoot = ownerPath.substringBeforeLast('/', "")
    if (runCatching { validateModelId("$ownerName/$modelName") }.getOrNull() == null) {
        throw ArtifactFileAccessException()
    }
    var current = openAbsoluteDirectory(modelsRoot, create)
    try {
        listOf(ownerName, modelName).forEach { segment ->
            var next = openat(current, segment, O_RDONLY or O_DIRECTORY or O_NOFOLLOW or O_CLOEXEC)
            if (next < 0 && create && errno == ENOENT) {
                if (mkdirat(current, segment, 448u) != 0 || fsync(current) != 0) throw ArtifactFileAccessException()
                next = openat(current, segment, O_RDONLY or O_DIRECTORY or O_NOFOLLOW or O_CLOEXEC)
            }
            if (next < 0) throw ArtifactFileAccessException()
            close(current)
            current = next
        }
        return memScoped {
            val metadata = alloc<stat>()
            if (fstat(current, metadata.ptr) != 0) throw ArtifactFileAccessException()
            PinnedRoot(current, metadata.st_dev.toULong(), metadata.st_ino.toULong()).also { current = -1 }
        }
    } finally {
        if (current >= 0) close(current)
    }
}

private fun openAbsoluteDirectory(path: String, create: Boolean = false): Int {
    if (!path.startsWith('/') || path.length > 4096) throw ArtifactFileAccessException()
    var current = open("/", O_RDONLY or O_DIRECTORY or O_NOFOLLOW or O_CLOEXEC)
    if (current < 0) throw ArtifactFileAccessException()
    try {
        path.split('/').filter { it.isNotEmpty() }.forEach { segment ->
            if (segment == "." || segment == "..") throw ArtifactFileAccessException()
            var next = openat(current, segment, O_RDONLY or O_DIRECTORY or O_NOFOLLOW or O_CLOEXEC)
            if (next < 0 && create && errno == ENOENT) {
                if (mkdirat(current, segment, 448u) != 0 || fsync(current) != 0) {
                    throw ArtifactFileAccessException()
                }
                next = openat(current, segment, O_RDONLY or O_DIRECTORY or O_NOFOLLOW or O_CLOEXEC)
            }
            if (next < 0) throw ArtifactFileAccessException()
            close(current)
            current = next
        }
        return current.also { current = -1 }
    } finally {
        if (current >= 0) close(current)
    }
}

private class DescriptorSink(private var descriptor: Int) : Sink {
    override fun write(source: Buffer, byteCount: Long) {
        require(byteCount >= 0L && byteCount <= source.size)
        var remaining = byteCount
        val bytes = ByteArray(minOf(64 * 1024L, byteCount.coerceAtLeast(1L)).toInt())
        while (remaining > 0L) {
            val count = minOf(bytes.size.toLong(), remaining).toInt()
            val chunk = source.readByteArray(count.toLong())
            var offset = 0
            chunk.usePinned { pinned ->
                while (offset < count) {
                    val written = write(descriptor, pinned.addressOf(offset), (count - offset).toULong())
                    if (written <= 0) throw ArtifactFileAccessException()
                    offset += written.toInt()
                }
            }
            remaining -= count
        }
    }

    override fun flush() = Unit
    override fun timeout(): Timeout = Timeout.NONE
    override fun close() {
        val current = descriptor
        descriptor = -1
        if (current >= 0 && close(current) != 0) throw ArtifactFileAccessException()
    }
}

private const val DARWIN_F_FULLFSYNC = 51

private class DescriptorSource(
    private var descriptor: Int,
    private val ownsDescriptor: Boolean,
) : Source {
    override fun read(sink: Buffer, byteCount: Long): Long {
        require(byteCount >= 0L)
        if (byteCount == 0L) return 0L
        val bytes = ByteArray(minOf(64 * 1024L, byteCount).toInt())
        val count = bytes.usePinned { pinned -> read(descriptor, pinned.addressOf(0), bytes.size.toULong()) }
        if (count < 0) throw ArtifactFileAccessException()
        if (count == 0L) return -1L
        sink.write(bytes, 0, count.toInt())
        return count
    }

    override fun timeout(): Timeout = Timeout.NONE
    override fun close() {
        if (!ownsDescriptor) return
        val current = descriptor
        descriptor = -1
        if (current >= 0) close(current)
    }
}
