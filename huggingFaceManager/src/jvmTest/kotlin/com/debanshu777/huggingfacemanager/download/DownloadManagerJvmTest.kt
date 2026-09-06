package com.debanshu777.huggingfacemanager.download

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okio.Path.Companion.toPath as toOkioPath
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadManagerJvmTest {
    @Test
    fun nonSuccessResponseDoesNotReplaceExistingModel() = withTemporaryRoot { root ->
        val original = byteArrayOf(1, 2, 3)
        val finalFile = modelFile(root, "org/model", "weights/model.gguf")
        finalFile.parentFile.mkdirs()
        finalFile.writeBytes(original)

        withServer { exchange ->
            exchange.respond(status = 404, declaredLength = 9L, body = "not found".encodeToByteArray())
        }.use { server ->
            val manager = DownloadManager(TestStoragePathProvider(root), server.baseUrl)

            assertFailsWith<DownloadHttpException> {
                runBlocking {
                    manager.download("org/model", "weights/model.gguf", metadata("weights/model.gguf", 9L)).toList()
                }
            }
        }

        assertContentEquals(original, finalFile.readBytes())
        assertFalse(File(finalFile.path + ".part").exists())
        assertFalse(File(finalFile.parentFile.parentFile, ArtifactManifestStore.MANIFEST_FILE_NAME).exists())
    }

    @Test
    fun truncatedResponseRemovesTemporaryFileAndPreservesExistingModel() = withTemporaryRoot { root ->
        val original = byteArrayOf(4, 5, 6)
        val finalFile = modelFile(root, "org/model", "model.gguf")
        finalFile.parentFile.mkdirs()
        finalFile.writeBytes(original)

        withServer { exchange ->
            exchange.respond(status = 200, declaredLength = 10L, body = byteArrayOf(9, 8, 7))
        }.use { server ->
            val manager = DownloadManager(TestStoragePathProvider(root), server.baseUrl)

            assertFails {
                runBlocking {
                    manager.download("org/model", "model.gguf", metadata("model.gguf", 10L)).toList()
                }
            }
        }

        assertContentEquals(original, finalFile.readBytes())
        assertFalse(File(finalFile.path + ".part").exists())
        assertFalse(File(finalFile.parentFile, ArtifactManifestStore.MANIFEST_FILE_NAME).exists())
    }

    @Test
    fun successfulResponseCommitsExactBytesAndPublishesFinalPath() = withTemporaryRoot { root ->
        val expected = ByteArray(32_768) { index -> (index % 251).toByte() }

        withServer { exchange ->
            exchange.respond(status = 200, declaredLength = expected.size.toLong(), body = expected)
        }.use { server ->
            val manager = DownloadManager(TestStoragePathProvider(root), server.baseUrl)
            val events = runBlocking {
                manager.download(
                    "org/model",
                    "weights/model.gguf",
                    metadata("weights/model.gguf", expected.size.toLong(), expected.sha256Hex()),
                ).toList()
            }
            val finalFile = modelFile(root, "org/model", "weights/model.gguf")

            assertTrue(finalFile.isFile)
            assertContentEquals(expected, finalFile.readBytes())
            assertFalse(File(finalFile.path + ".part").exists())
            assertEquals(finalFile.canonicalPath, events.last().localPath)
            assertEquals(100f, events.last().percentage)
            assertEquals(expected.sha256Hex(), events.last().contentSha256)
            val manifest = ArtifactManifestStore(finalFile.parentFile.parentFile.absolutePath.toOkioPath()).read()
            assertEquals(expected.sha256Hex(), manifest?.entries?.single()?.contentSha256)
            assertEquals(
                manifest,
                runBlocking { manager.validatedArtifacts("org/model") },
            )
        }
    }

    @Test
    fun requestUsesImmutableRevisionAndEncodedRelativePath() = withTemporaryRoot { root ->
        val requestedPath = AtomicReference<String>()
        val expected = byteArrayOf(7)
        withServer { exchange ->
            requestedPath.set(exchange.requestURI.rawPath)
            exchange.respond(status = 200, declaredLength = 1L, body = expected)
        }.use { server ->
            val manager = DownloadManager(TestStoragePathProvider(root), server.baseUrl)
            runBlocking {
                manager.download(
                    "org/model",
                    "weights/model file.gguf",
                    metadata("weights/model file.gguf", 1L, expected.sha256Hex()),
                ).toList()
            }
        }

        assertEquals(
            "/org/model/resolve/${"a".repeat(40)}/weights/model%20file.gguf",
            requestedPath.get(),
        )
    }

    @Test
    fun mismatchedLegacyArgumentsFailBeforeNetworkAccess() = withTemporaryRoot { root ->
        val requests = AtomicInteger()
        val storageResolutions = AtomicInteger()
        withServer { exchange ->
            requests.incrementAndGet()
            exchange.respond(status = 200, declaredLength = 1L, body = byteArrayOf(1))
        }.use { server ->
            val manager = DownloadManager(TestStoragePathProvider(root, storageResolutions), server.baseUrl)

            assertFailsWith<IllegalArgumentException> {
                runBlocking {
                    manager.download("other/model", "model.gguf", metadata("model.gguf", 1L)).toList()
                }
            }
        }

        assertEquals(0, requests.get())
        assertEquals(0, storageResolutions.get())
    }

    @Test
    fun concurrentFilesInOneModelRootPublishOneCompleteManifest() = withTemporaryRoot { root ->
        val first = "first".encodeToByteArray()
        val second = "second".encodeToByteArray()
        withServer { exchange ->
            val body = if (exchange.requestURI.path.endsWith("first.gguf")) first else second
            exchange.respond(status = 200, declaredLength = body.size.toLong(), body = body)
        }.use { server ->
            val manager = DownloadManager(TestStoragePathProvider(root), server.baseUrl)
            runBlocking {
                val one = async {
                    manager.download(
                        "org/model",
                        "first.gguf",
                        metadata("first.gguf", first.size.toLong(), first.sha256Hex(), role = "part-1"),
                    ).toList()
                }
                val two = async {
                    manager.download(
                        "org/model",
                        "second.gguf",
                        metadata("second.gguf", second.size.toLong(), second.sha256Hex(), role = "part-2"),
                    ).toList()
                }
                one.await()
                two.await()
            }
        }

        val modelRoot = File(root, "models/org/model")
        val manifest = ArtifactManifestStore(modelRoot.absolutePath.toOkioPath()).read()
        assertEquals(setOf("part-1", "part-2"), manifest?.entries?.map { it.logicalRole }?.toSet())
    }

    @Test
    fun unsafePathFailsBeforeNetworkAccess() = withTemporaryRoot { root ->
        val requests = AtomicInteger()
        withServer { exchange ->
            requests.incrementAndGet()
            exchange.respond(status = 200, declaredLength = 1L, body = byteArrayOf(1))
        }.use { server ->
            val manager = DownloadManager(TestStoragePathProvider(root), server.baseUrl)

            assertFailsWith<IllegalArgumentException> {
                runBlocking {
                    manager.download("org/model", "../escape.gguf", metadata("model.gguf", 1L)).toList()
                }
            }
        }

        assertEquals(0, requests.get())
        assertFalse(File(root.parentFile, "escape.gguf").exists())
    }

    private fun metadata(
        path: String,
        expectedBytes: Long,
        digest: String? = null,
        role: String = "model",
    ) = DownloadMetadataDTO(
        artifact = requireNotNull(
            DownloadArtifactIdentity.create(
                repositoryId = "org/model",
                immutableRevision = "a".repeat(40),
                relativePath = path,
                remoteObjectId = digest?.let { "sha256:$it" },
                expectedBytes = expectedBytes,
            ),
        ),
        logicalRole = role,
        sizeBytes = expectedBytes,
        author = null,
        libraryName = null,
        pipelineTag = null,
    )
}

private fun ByteArray.sha256Hex(): String = okio.ByteString.of(*this).sha256().hex()

private class TestServer(
    private val server: HttpServer,
) : AutoCloseable {
    val baseUrl: String = "http://127.0.0.1:${server.address.port}"

    override fun close() {
        server.stop(0)
    }
}

private fun withServer(handler: (HttpExchange) -> Unit): TestServer {
    val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    server.createContext("/") { exchange ->
        try {
            handler(exchange)
        } finally {
            exchange.close()
        }
    }
    server.start()
    return TestServer(server)
}

private fun HttpExchange.respond(status: Int, declaredLength: Long, body: ByteArray) {
    sendResponseHeaders(status, declaredLength)
    responseBody.use { output -> output.write(body) }
}

private inline fun <T> withTemporaryRoot(block: (File) -> T): T {
    val root = Files.createTempDirectory("caraml-download-test").toRealPath().toFile()
    return try {
        block(root)
    } finally {
        root.deleteRecursively()
    }
}

private fun modelFile(root: File, modelId: String, relativePath: String): File =
    File(File(root, "models/$modelId"), relativePath)

private class TestStoragePathProvider(
    private val root: File,
    private val storageResolutions: AtomicInteger? = null,
) : StoragePathProvider {
    override fun getModelsStorageDirectory(modelId: String): String =
        File(File(root, "models").apply { mkdirs() }, modelId)
            .also { storageResolutions?.incrementAndGet() }.absolutePath

    override fun getDatabasePath(): String = File(root, "caraml.db").absolutePath
    override fun fileExists(path: String): Boolean = File(path).exists()
    override fun getAvailableStorageBytes(): Long = Long.MAX_VALUE
    override fun getTotalStorageBytes(): Long = Long.MAX_VALUE
    override fun isModelFileReadable(path: String): Boolean = File(path).isFile
    override fun isDirectoryReadable(path: String): Boolean = File(path).isDirectory
    override fun getFileSize(path: String): Long = File(path).takeIf { it.exists() }?.length() ?: 0L

    override fun renameFile(from: String, to: String): Boolean = try {
        val source = File(from).toPath()
        val destination = File(to).toPath()
        destination.parent?.let(Files::createDirectories)
        try {
            Files.move(
                source,
                destination,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: Exception) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
        }
        true
    } catch (_: Exception) {
        false
    }

    override fun deleteDownloadedModelContent(modelId: String, localPath: String): Boolean =
        File(localPath).deleteRecursively()
}
