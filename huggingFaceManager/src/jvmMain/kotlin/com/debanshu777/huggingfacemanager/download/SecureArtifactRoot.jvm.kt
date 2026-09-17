package com.debanshu777.huggingfacemanager.download

import okio.Buffer
import okio.HashingSource
import okio.Path
import okio.Sink
import okio.Source
import okio.Timeout
import java.io.File

internal actual class SecureArtifactRoot private constructor(private var handle: Long) {
    actual constructor(modelRoot: Path) : this(openExisting(modelRoot))

    actual companion object {
        actual fun create(modelsRoot: Path, modelId: String): SecureArtifactRoot {
            val validatedModelId = validateModelId(modelId)
            val absoluteRoot = normalizedAbsolute(modelsRoot)
            return SecureArtifactRoot(
                NativeArtifactFs.openRoot(absoluteRoot.nativeUtf8(), validatedModelId.nativeUtf8(), true)
                    .takeIf { it != 0L } ?: throw ArtifactFileAccessException(),
            )
        }

        private fun openExisting(modelRoot: Path): Long {
            val absolute = normalizedAbsolute(modelRoot)
            val modelName = absolute.substringAfterLast('/')
            val ownerPath = absolute.substringBeforeLast('/', missingDelimiterValue = "")
            val ownerName = ownerPath.substringAfterLast('/')
            val modelsRoot = ownerPath.substringBeforeLast('/', missingDelimiterValue = "")
            val modelId = runCatching { validateModelId("$ownerName/$modelName") }.getOrNull()
                ?: throw ArtifactFileAccessException()
            return NativeArtifactFs.openRoot(modelsRoot.nativeUtf8(), modelId.nativeUtf8(), true)
                .takeIf { it != 0L } ?: throw ArtifactFileAccessException()
        }

        private fun normalizedAbsolute(path: Path): String {
            val value = File(path.toString()).toPath().toAbsolutePath().normalize().toString().replace('\\', '/')
            val isWindowsAbsolute = value.length >= 3 && value[1] == ':' && value[2] == '/'
            if ((!value.startsWith('/') && !isWindowsAbsolute) || value.length > 4096) {
                throw ArtifactFileAccessException()
            }
            return value.trimEnd('/')
        }
    }

    actual fun createParentDirectories(relativePath: String) {
        checkedRelative(relativePath)
        requireNative(NativeArtifactFs.createParents(activeHandle(), relativePath.nativeUtf8()))
    }

    actual fun sink(relativePath: String, mustCreate: Boolean): Sink {
        checkedRelative(relativePath)
        val descriptor = NativeArtifactFs.openFile(activeHandle(), relativePath.nativeUtf8(), if (mustCreate) 1 else 2)
        if (descriptor < 0L) throw ArtifactFileAccessException()
        return NativeDescriptorSink(activeHandle(), descriptor)
    }

    actual fun appendSink(relativePath: String, expectedOffset: Long): Sink {
        checkedRelative(relativePath)
        if (expectedOffset <= 0L || size(relativePath) != expectedOffset) throw ArtifactFileAccessException()
        val descriptor = NativeArtifactFs.openFile(activeHandle(), relativePath.nativeUtf8(), 3)
        if (descriptor < 0L) throw ArtifactFileAccessException()
        return NativeDescriptorSink(activeHandle(), descriptor)
    }

    actual fun existsRegularFile(relativePath: String): Boolean = size(relativePath) != null

    actual fun size(relativePath: String): Long? {
        checkedRelative(relativePath)
        return NativeArtifactFs.size(activeHandle(), relativePath.nativeUtf8()).takeIf { it >= 0L }
    }

    actual fun readBounded(relativePath: String, maxBytes: Int): ByteArray? {
        if (maxBytes <= 0 || maxBytes > ArtifactManifestStore.MAX_MANIFEST_BYTES) return null
        return runCatching {
            openSource(relativePath).use { source ->
                val buffer = Buffer()
                val limit = maxBytes.toLong() + 1L
                while (buffer.size < limit) {
                    val read = source.read(buffer, minOf(8_192L, limit - buffer.size))
                    if (read == -1L) break
                }
                buffer.readByteArray().takeIf { it.size in 1..maxBytes }
            }
        }.getOrNull()
    }

    actual fun sha256(relativePath: String, expectedBytes: Long): String? {
        if (expectedBytes <= 0L || size(relativePath) != expectedBytes) return null
        return runCatching {
            val hashing = HashingSource.sha256(openSource(relativePath))
            hashing.use { source ->
                val buffer = Buffer()
                var total = 0L
                while (total <= expectedBytes) {
                    val count = source.read(buffer, minOf(256L * 1024L, expectedBytes + 1L - total))
                    if (count == -1L) break
                    total += count
                    buffer.clear()
                }
                if (total == expectedBytes) hashing.hash.hex() else null
            }
        }.getOrNull()
    }

    actual fun atomicMove(sourceRelativePath: String, targetRelativePath: String) {
        checkedRelative(sourceRelativePath)
        checkedRelative(targetRelativePath)
        requireNative(
            NativeArtifactFs.move(activeHandle(), sourceRelativePath.nativeUtf8(), targetRelativePath.nativeUtf8()),
        )
    }

    actual fun delete(relativePath: String) {
        checkedRelative(relativePath)
        requireNative(NativeArtifactFs.delete(activeHandle(), relativePath.nativeUtf8()))
    }

    actual fun syncFile(relativePath: String) {
        checkedRelative(relativePath)
        requireNative(NativeArtifactFs.syncFile(activeHandle(), relativePath.nativeUtf8()))
    }

    actual fun syncDirectory(relativePath: String) {
        if (relativePath != ".") checkedRelative(relativePath)
        requireNative(NativeArtifactFs.syncDirectory(activeHandle(), relativePath.nativeUtf8()))
    }

    actual fun revalidate() {
        if (!NativeArtifactFs.revalidate(activeHandle())) throw ArtifactFileAccessException()
    }

    actual fun close() {
        val current = handle
        handle = 0L
        if (current != 0L) NativeArtifactFs.closeRoot(current)
    }

    private fun openSource(relativePath: String): Source {
        checkedRelative(relativePath)
        val descriptor = NativeArtifactFs.openFile(activeHandle(), relativePath.nativeUtf8(), 0)
        if (descriptor < 0L) throw ArtifactFileAccessException()
        return NativeDescriptorSource(activeHandle(), descriptor)
    }

    private fun activeHandle(): Long = handle.takeIf { it != 0L } ?: throw ArtifactFileAccessException()

    private fun checkedRelative(relativePath: String) {
        val validated = runCatching { validateDownloadRequest("owner/model", relativePath).relativePath }.getOrNull()
        if (validated != relativePath || relativePath.length > 4096) throw ArtifactFileAccessException()
    }

    private fun requireNative(value: Boolean) {
        if (!value || !NativeArtifactFs.revalidate(activeHandle())) throw ArtifactFileAccessException()
    }
}

private class NativeDescriptorSink(
    private val rootHandle: Long,
    private var descriptor: Long,
) : Sink {
    override fun write(source: Buffer, byteCount: Long) {
        require(byteCount >= 0L && byteCount <= source.size)
        var remaining = byteCount
        while (remaining > 0L) {
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
        descriptor = -1L
        if (current >= 0L && (!NativeArtifactFs.closeFile(current) || !NativeArtifactFs.revalidate(rootHandle))) {
            throw ArtifactFileAccessException()
        }
    }
}

private class NativeDescriptorSource(
    private val rootHandle: Long,
    private var descriptor: Long,
) : Source {
    override fun read(sink: Buffer, byteCount: Long): Long {
        require(byteCount >= 0L)
        if (byteCount == 0L) return 0L
        val bytes = ByteArray(minOf(64L * 1024L, byteCount).toInt())
        val count = NativeArtifactFs.read(descriptor, bytes, 0, bytes.size)
        if (count < 0) throw ArtifactFileAccessException()
        if (count == 0) return -1L
        sink.write(bytes, 0, count)
        return count.toLong()
    }

    override fun timeout(): Timeout = Timeout.NONE
    override fun close() {
        val current = descriptor
        descriptor = -1L
        if (current >= 0L && (!NativeArtifactFs.closeFile(current) || !NativeArtifactFs.revalidate(rootHandle))) {
            throw ArtifactFileAccessException()
        }
    }
}

private object NativeArtifactFs {
    init {
        val mapped = System.mapLibraryName("artifact_fs")
        val packaged = System.getProperty("compose.application.resources.dir")
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it, mapped) }
        val directory = System.getProperty("caraml.native.lib.dir")?.takeIf { it.isNotBlank() }
        when {
            packaged?.isFile == true -> System.load(packaged.absolutePath)
            directory != null -> System.load(File(directory, mapped).absolutePath)
            else -> System.loadLibrary("artifact_fs")
        }
    }

    external fun openRoot(modelsRoot: ByteArray, modelId: ByteArray, create: Boolean): Long
    external fun closeRoot(handle: Long)
    external fun revalidate(handle: Long): Boolean
    external fun createParents(handle: Long, relativePath: ByteArray): Boolean
    external fun openFile(handle: Long, relativePath: ByteArray, mode: Int): Long
    external fun read(descriptor: Long, output: ByteArray, offset: Int, count: Int): Int
    external fun write(descriptor: Long, input: ByteArray, offset: Int, count: Int): Int
    external fun closeFile(descriptor: Long): Boolean
    external fun size(handle: Long, relativePath: ByteArray): Long
    external fun move(handle: Long, source: ByteArray, target: ByteArray): Boolean
    external fun delete(handle: Long, relativePath: ByteArray): Boolean
    external fun syncFile(handle: Long, relativePath: ByteArray): Boolean
    external fun syncDirectory(handle: Long, relativePath: ByteArray): Boolean
}

private fun String.nativeUtf8(): ByteArray {
    if (isEmpty() || '\u0000' in this || !hasWellFormedUtf16()) throw ArtifactFileAccessException()
    return encodeToByteArray().takeIf { it.size <= 4_096 } ?: throw ArtifactFileAccessException()
}

private fun String.hasWellFormedUtf16(): Boolean {
    var index = 0
    while (index < length) {
        when {
            this[index].isHighSurrogate() -> {
                if (index + 1 >= length || !this[index + 1].isLowSurrogate()) return false
                index += 2
            }
            this[index].isLowSurrogate() -> return false
            else -> index += 1
        }
    }
    return true
}
