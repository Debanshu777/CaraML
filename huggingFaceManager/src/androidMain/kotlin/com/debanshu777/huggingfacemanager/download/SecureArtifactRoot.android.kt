package com.debanshu777.huggingfacemanager.download

import okio.Buffer
import okio.HashingSource
import okio.Path
import okio.Sink
import okio.Source
import okio.Timeout
import java.io.File

internal actual class SecureArtifactRoot private constructor(private var handle: Long) {
    actual constructor(modelRoot: Path) : this(openRoot(modelRoot))

    actual companion object {
        actual fun create(modelsRoot: Path, modelId: String): SecureArtifactRoot {
            val id = validateModelId(modelId)
            val root = normalizedAbsolute(modelsRoot)
            return SecureArtifactRoot(
                NativeArtifactFs.openRoot(root, id, true).takeIf { it != 0L }
                    ?: throw ArtifactFileAccessException(),
            )
        }

        private fun openRoot(modelRoot: Path): Long {
            val absolute = normalizedAbsolute(modelRoot)
            val name = absolute.substringAfterLast('/')
            val ownerPath = absolute.substringBeforeLast('/', "")
            val owner = ownerPath.substringAfterLast('/')
            val modelsRoot = ownerPath.substringBeforeLast('/', "")
            val id = runCatching { validateModelId("$owner/$name") }.getOrNull()
                ?: throw ArtifactFileAccessException()
            return NativeArtifactFs.openRoot(modelsRoot, id, true).takeIf { it != 0L }
                ?: throw ArtifactFileAccessException()
        }

        private fun normalizedAbsolute(path: Path): String {
            val value = File(path.toString()).toPath().toAbsolutePath().normalize().toString().replace('\\', '/')
            if (!value.startsWith('/') || value.length > 4096) throw ArtifactFileAccessException()
            return value.trimEnd('/')
        }
    }

    actual fun createParentDirectories(relativePath: String) {
        checked(relativePath)
        requireNative(NativeArtifactFs.createParents(active(), relativePath))
    }

    actual fun sink(relativePath: String, mustCreate: Boolean): Sink {
        checked(relativePath)
        val descriptor = NativeArtifactFs.openFile(active(), relativePath, if (mustCreate) 1 else 2)
        if (descriptor < 0L) throw ArtifactFileAccessException()
        return DescriptorSink(active(), descriptor)
    }

    actual fun existsRegularFile(relativePath: String): Boolean = size(relativePath) != null

    actual fun size(relativePath: String): Long? {
        checked(relativePath)
        return NativeArtifactFs.size(active(), relativePath).takeIf { it >= 0L }
    }

    actual fun readBounded(relativePath: String, maxBytes: Int): ByteArray? {
        if (maxBytes <= 0 || maxBytes > ArtifactManifestStore.MAX_MANIFEST_BYTES) return null
        return runCatching {
            source(relativePath).use { input ->
                val buffer = Buffer()
                val limit = maxBytes.toLong() + 1L
                while (buffer.size < limit) {
                    val read = input.read(buffer, minOf(8_192L, limit - buffer.size))
                    if (read == -1L) break
                }
                buffer.readByteArray().takeIf { it.size in 1..maxBytes }
            }
        }.getOrNull()
    }

    actual fun sha256(relativePath: String, expectedBytes: Long): String? {
        if (expectedBytes <= 0 || size(relativePath) != expectedBytes) return null
        return runCatching {
            val hashing = HashingSource.sha256(source(relativePath))
            hashing.use { input ->
                val buffer = Buffer()
                var total = 0L
                while (total <= expectedBytes) {
                    val read = input.read(buffer, minOf(256L * 1024L, expectedBytes + 1L - total))
                    if (read == -1L) break
                    total += read
                    buffer.clear()
                }
                if (total == expectedBytes) hashing.hash.hex() else null
            }
        }.getOrNull()
    }

    actual fun atomicMove(sourceRelativePath: String, targetRelativePath: String) {
        checked(sourceRelativePath)
        checked(targetRelativePath)
        requireNative(NativeArtifactFs.move(active(), sourceRelativePath, targetRelativePath))
    }

    actual fun delete(relativePath: String) {
        checked(relativePath)
        requireNative(NativeArtifactFs.delete(active(), relativePath))
    }

    actual fun syncFile(relativePath: String) {
        checked(relativePath)
        requireNative(NativeArtifactFs.syncFile(active(), relativePath))
    }

    actual fun syncDirectory(relativePath: String) {
        if (relativePath != ".") checked(relativePath)
        requireNative(NativeArtifactFs.syncDirectory(active(), relativePath))
    }

    actual fun revalidate() {
        if (!NativeArtifactFs.revalidate(active())) throw ArtifactFileAccessException()
    }

    actual fun close() {
        val current = handle
        handle = 0
        if (current != 0L) NativeArtifactFs.closeRoot(current)
    }

    private fun source(relativePath: String): Source {
        checked(relativePath)
        val descriptor = NativeArtifactFs.openFile(active(), relativePath, 0)
        if (descriptor < 0L) throw ArtifactFileAccessException()
        return DescriptorSource(active(), descriptor)
    }

    private fun active(): Long = handle.takeIf { it != 0L } ?: throw ArtifactFileAccessException()

    private fun checked(path: String) {
        val validated = runCatching { validateDownloadRequest("owner/model", path).relativePath }.getOrNull()
        if (validated != path || path.length > 4096) throw ArtifactFileAccessException()
    }

    private fun requireNative(result: Boolean) {
        if (!result || !NativeArtifactFs.revalidate(active())) throw ArtifactFileAccessException()
    }
}

private class DescriptorSink(private val root: Long, private var descriptor: Long) : Sink {
    override fun write(source: Buffer, byteCount: Long) {
        require(byteCount >= 0 && byteCount <= source.size)
        var remaining = byteCount
        while (remaining > 0) {
            val count = minOf(64L * 1024L, remaining).toInt()
            val bytes = source.readByteArray(count.toLong())
            var offset = 0
            while (offset < count) {
                val written = NativeArtifactFs.write(descriptor, bytes, offset, count - offset)
                if (written <= 0) throw ArtifactFileAccessException()
                offset += written
            }
            remaining -= count
        }
    }

    override fun flush() = Unit
    override fun timeout(): Timeout = Timeout.NONE
    override fun close() {
        val current = descriptor
        descriptor = -1
        if (current >= 0 && (!NativeArtifactFs.closeFile(current) || !NativeArtifactFs.revalidate(root))) {
            throw ArtifactFileAccessException()
        }
    }
}

private class DescriptorSource(private val root: Long, private var descriptor: Long) : Source {
    override fun read(sink: Buffer, byteCount: Long): Long {
        require(byteCount >= 0)
        if (byteCount == 0L) return 0
        val bytes = ByteArray(minOf(64L * 1024L, byteCount).toInt())
        val count = NativeArtifactFs.read(descriptor, bytes, 0, bytes.size)
        if (count < 0) throw ArtifactFileAccessException()
        if (count == 0) return -1
        sink.write(bytes, 0, count)
        return count.toLong()
    }

    override fun timeout(): Timeout = Timeout.NONE
    override fun close() {
        val current = descriptor
        descriptor = -1
        if (current >= 0 && (!NativeArtifactFs.closeFile(current) || !NativeArtifactFs.revalidate(root))) {
            throw ArtifactFileAccessException()
        }
    }
}

private object NativeArtifactFs {
    init { System.loadLibrary("artifact_fs") }

    external fun openRoot(modelsRoot: String, modelId: String, create: Boolean): Long
    external fun closeRoot(handle: Long)
    external fun revalidate(handle: Long): Boolean
    external fun createParents(handle: Long, relativePath: String): Boolean
    external fun openFile(handle: Long, relativePath: String, mode: Int): Long
    external fun read(descriptor: Long, output: ByteArray, offset: Int, count: Int): Int
    external fun write(descriptor: Long, input: ByteArray, offset: Int, count: Int): Int
    external fun closeFile(descriptor: Long): Boolean
    external fun size(handle: Long, relativePath: String): Long
    external fun move(handle: Long, source: String, target: String): Boolean
    external fun delete(handle: Long, relativePath: String): Boolean
    external fun syncFile(handle: Long, relativePath: String): Boolean
    external fun syncDirectory(handle: Long, relativePath: String): Boolean
}
