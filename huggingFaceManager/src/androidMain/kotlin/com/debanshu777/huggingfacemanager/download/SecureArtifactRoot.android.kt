package com.debanshu777.huggingfacemanager.download

import okio.Buffer
import okio.HashingSource
import okio.Path
import okio.Sink
import okio.sink
import okio.source
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.BasicFileAttributeView
import kotlin.io.path.Path as NioPath

internal actual class SecureArtifactRoot actual constructor(modelRoot: Path) {
    private val rootPath = NioPath(modelRoot.toString())
    private val rootIdentity: Any
    private val root: SecureDirectoryStream<java.nio.file.Path>

    init {
        try {
            root = openSecureRoot(rootPath)
            rootIdentity = root.getFileAttributeView(
                NioPath("."),
                BasicFileAttributeView::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )?.readAttributes()?.fileKey() ?: throw ArtifactFileAccessException()
            verifyRootIdentity()
        } catch (error: ArtifactFileAccessException) {
            throw error
        } catch (_: Exception) {
            throw ArtifactFileAccessException()
        }
    }

    actual fun createParentDirectories(relativePath: String) {
        protect { withSecureParent(relativePath) { _, _ -> Unit } }
    }

    actual fun sink(relativePath: String, mustCreate: Boolean): Sink = protect {
        val options = linkedSetOf<OpenOption>(StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
        if (mustCreate) options += StandardOpenOption.CREATE_NEW
        else options += listOf(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        Channels.newOutputStream(openChannel(relativePath, options)).sink()
    }

    actual fun existsRegularFile(relativePath: String): Boolean = try {
        withChannel(relativePath, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) { true }
    } catch (_: ArtifactFileAccessException) {
        false
    }

    actual fun size(relativePath: String): Long? = try {
        withChannel(relativePath, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) { it.size() }
    } catch (_: ArtifactFileAccessException) {
        null
    }

    actual fun readBounded(relativePath: String, maxBytes: Int): ByteArray? = try {
        withChannel(relativePath, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) { channel ->
            val source = Channels.newInputStream(channel).source()
            val sink = Buffer()
            val limit = maxBytes.toLong() + 1L
            while (sink.size < limit) {
                val read = source.read(sink, minOf(8_192L, limit - sink.size))
                if (read == -1L) break
            }
            sink.readByteArray().takeIf { it.size in 1..maxBytes }
        }
    } catch (_: ArtifactFileAccessException) {
        null
    }

    actual fun sha256(relativePath: String, expectedBytes: Long): String? = try {
        withChannel(relativePath, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) { channel ->
            if (channel.size() != expectedBytes) return@withChannel null
            val hashing = HashingSource.sha256(Channels.newInputStream(channel).source())
            val sink = Buffer()
            var total = 0L
            while (total <= expectedBytes) {
                val read = hashing.read(sink, minOf(256L * 1024L, expectedBytes + 1L - total))
                if (read == -1L) break
                total += read
                sink.clear()
            }
            if (total == expectedBytes) hashing.hash.hex() else null
        }
    } catch (_: ArtifactFileAccessException) {
        null
    }

    actual fun atomicMove(sourceRelativePath: String, targetRelativePath: String) = protect {
        withSecureParent(sourceRelativePath) { sourceParent, sourceName ->
            withSecureParent(targetRelativePath) { targetParent, targetName ->
                sourceParent.move(sourceName, targetParent, targetName)
            }
        }
    }

    actual fun delete(relativePath: String) {
        try {
            protect { withSecureParent(relativePath) { parent, name -> parent.deleteFile(name) } }
        } catch (_: ArtifactFileAccessException) {
            if (existsRegularFile(relativePath)) throw ArtifactFileAccessException()
        }
    }

    actual fun syncFile(relativePath: String) = protect {
        withChannel(relativePath, setOf(StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            (it as? FileChannel)?.force(true) ?: throw ArtifactFileAccessException()
        }
    }

    actual fun syncDirectory(relativePath: String) = protect {
        val syncChannel: (SeekableByteChannel) -> Unit = { channel ->
            (channel as? FileChannel)?.force(true) ?: throw ArtifactFileAccessException()
        }
        if (relativePath == ".") {
            root.newByteChannel(
                NioPath("."),
                setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
            ).use(syncChannel)
        } else {
            withSecureParent(relativePath) { parent, name ->
                parent.newByteChannel(
                    name,
                    setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
                ).use(syncChannel)
            }
        }
    }

    actual fun close() {
        runCatching { root.close() }
    }

    private fun <T> withChannel(
        relativePath: String,
        options: Set<OpenOption>,
        block: (SeekableByteChannel) -> T,
    ): T = protect { openChannel(relativePath, options).use(block) }

    private fun openChannel(relativePath: String, options: Set<OpenOption>): SeekableByteChannel =
        withSecureParent(relativePath) { parent, name -> parent.newByteChannel(name, options) }

    private fun <T> withSecureParent(
        relativePath: String,
        block: (SecureDirectoryStream<java.nio.file.Path>, java.nio.file.Path) -> T,
    ): T {
        verifyRootIdentity()
        val segments = validatedSegments(relativePath)
        var current = root
        val opened = mutableListOf<SecureDirectoryStream<java.nio.file.Path>>()
        try {
            segments.dropLast(1).forEach { segment ->
                current = current.newDirectoryStream(NioPath(segment), LinkOption.NOFOLLOW_LINKS).also(opened::add)
            }
            return block(current, NioPath(segments.last()))
        } finally {
            opened.asReversed().forEach { runCatching { it.close() } }
            verifyRootIdentity()
        }
    }

    private fun validatedSegments(relativePath: String): List<String> {
        val validated = runCatching { validateDownloadRequest("owner/model", relativePath).relativePath }
            .getOrNull() ?: throw ArtifactFileAccessException()
        if (validated != relativePath) throw ArtifactFileAccessException()
        return validated.split('/')
    }

    private fun verifyRootIdentity() {
        val current = Files.readAttributes(rootPath, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!current.isDirectory || current.isSymbolicLink || current.fileKey() != rootIdentity) {
            throw ArtifactFileAccessException()
        }
    }

    private fun openSecureRoot(path: java.nio.file.Path): SecureDirectoryStream<java.nio.file.Path> {
        val absolute = path.toAbsolutePath().normalize()
        val filesystemRoot = absolute.root ?: throw ArtifactFileAccessException()
        var current: SecureDirectoryStream<java.nio.file.Path>? =
            Files.newDirectoryStream(filesystemRoot) as? SecureDirectoryStream<java.nio.file.Path>
                ?: throw ArtifactFileAccessException()
        try {
            absolute.forEach { segment ->
                val next = requireNotNull(current).newDirectoryStream(segment, LinkOption.NOFOLLOW_LINKS)
                current.close()
                current = next
            }
            return requireNotNull(current).also { current = null }
        } finally {
            current?.let { runCatching { it.close() } }
        }
    }

    private inline fun <T> protect(block: () -> T): T = try {
        block()
    } catch (error: ArtifactFileAccessException) {
        throw error
    } catch (_: Exception) {
        throw ArtifactFileAccessException()
    }
}
