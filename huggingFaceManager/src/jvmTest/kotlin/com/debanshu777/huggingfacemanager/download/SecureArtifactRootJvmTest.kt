package com.debanshu777.huggingfacemanager.download

import okio.buffer
import okio.Path.Companion.toOkioPath
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecureArtifactRootJvmTest {
    @Test
    fun symlinkedParentIsRejectedWithoutWritingOutsideThePinnedRoot() = withRoots { root, outside ->
        Files.createSymbolicLink(root.resolve("nested"), outside)
        val secure = SecureArtifactRoot(root.toOkioPath())

        assertFailsWith<ArtifactFileAccessException> {
            secure.sink("nested/model.gguf.part", mustCreate = true).buffer().use { it.writeUtf8("blocked") }
        }

        assertFalse(outside.resolve("model.gguf.part").exists())
        secure.close()
    }

    @Test
    fun replacingTheRootPathAfterPinningFailsClosed() = withRoots { root, outside ->
        val secure = SecureArtifactRoot(root.toOkioPath())
        val moved = root.resolveSibling("pinned-moved")
        Files.move(root, moved)
        Files.createSymbolicLink(root, outside)

        assertFailsWith<ArtifactFileAccessException> {
            secure.sink("model.gguf.part", mustCreate = true).buffer().use { it.writeUtf8("blocked") }
        }

        assertFalse(outside.resolve("model.gguf.part").exists())
        secure.close()
    }

    @Test
    fun secureBoundedReadUsesOneNoFollowHandleAndStopsAtLimitPlusOne() = withRoots { root, _ ->
        Files.write(root.resolve("manifest"), ByteArray(300_000) { 1 })
        val secure = SecureArtifactRoot(root.toOkioPath())

        assertNull(secure.readBounded("manifest", 256 * 1024))

        secure.close()
    }

    @Test
    fun supportedHostCreatesNestedParentsThroughPinnedDescriptors() = withRoots { root, outside ->
        val secure = SecureArtifactRoot(root.toOkioPath())

        secure.createParentDirectories("one/two/model.gguf.part")
        secure.sink("one/two/model.gguf.part", mustCreate = true).buffer().use { it.writeUtf8("safe") }

        assertTrue(root.resolve("one/two/model.gguf.part").exists())
        assertFalse(outside.resolve("model.gguf.part").exists())
        secure.close()
    }

    @Test
    fun firstInstallCreatesModelsAndModelRootsThroughTheNativePinnedWalk() {
        val trusted = Files.createTempDirectory("caraml-first-install").toRealPath()
        val modelsRoot = trusted.resolve("fixed-app").resolve("models")
        try {
            val secure = SecureArtifactRoot.create(modelsRoot.toOkioPath(), "org/model")
            secure.createParentDirectories("nested/model.gguf.part")
            secure.sink("nested/model.gguf.part", mustCreate = true).buffer().use { it.writeUtf8("safe") }
            secure.syncDirectory("nested")
            secure.revalidate()

            assertTrue(modelsRoot.resolve("org/model/nested/model.gguf.part").exists())
            secure.close()
        } finally {
            trusted.toFile().deleteRecursively()
        }
    }

    @Test
    fun firstInstallResumesAfterEveryPreviouslyDurableRootComponent() {
        repeat(5) { durableComponents ->
            val trusted = Files.createTempDirectory("caraml-first-install-recovery-").toRealPath()
            val components = listOf("fixed-app", "models", "org", "model")
            var partial = trusted
            try {
                components.take(durableComponents.coerceAtMost(components.size)).forEach { component ->
                    partial = Files.createDirectory(partial.resolve(component))
                }
                val modelsRoot = trusted.resolve("fixed-app/models")
                val secure = SecureArtifactRoot.create(modelsRoot.toOkioPath(), "org/model")
                secure.sink("first.part", mustCreate = true).buffer().use { it.writeUtf8("safe") }
                secure.revalidate()

                assertTrue(modelsRoot.resolve("org/model/first.part").exists())
                secure.close()
            } finally {
                trusted.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun standardUtf8BmpAndEmojiPathRoundTripsThroughNativeManifestAndJava() = withRoots { root, _ ->
        val path = "weights/模型-😀.gguf"
        val bytes = "unicode-content".encodeToByteArray()
        val identity = requireNotNull(
            DownloadArtifactIdentity.create(
                repositoryId = "org/model",
                immutableRevision = "a".repeat(40),
                relativePath = path,
                remoteObjectId = "sha256:${bytes.sha256HexForSecureRootTest()}",
                expectedBytes = bytes.size.toLong(),
            ),
        )
        val bundleId = requireNotNull(artifactBundleId(listOf(identity)))
        val location = immutableArtifactStorageLocation(identity, bundleId)
        val secure = SecureArtifactRoot(root.toOkioPath())
        secure.createParentDirectories("${location.localRelativePath}.part")
        secure.sink("${location.localRelativePath}.part", mustCreate = true).buffer().use { it.write(bytes) }
        secure.close()
        val entry = requireNotNull(
            ArtifactManifestEntry.create(
                "model",
                identity,
                bytes.size.toLong(),
                bytes.sha256HexForSecureRootTest(),
                bundleId,
                location.localRelativePath,
                location.layoutRelativePath,
            ),
        )
        val store = ArtifactManifestStore(root.toOkioPath())

        store.commit(location.localRelativePath, entry)

        assertTrue(root.resolve(location.localRelativePath).exists())
        assertTrue(Files.readAllBytes(root.resolve(location.localRelativePath)).contentEquals(bytes))
        assertTrue(store.readValidated()?.entries?.single()?.identity == identity)
        store.close()
    }

    @Test
    fun malformedSurrogatePathIsRejectedBeforeTheNativeBoundary() = withRoots { root, _ ->
        val secure = SecureArtifactRoot(root.toOkioPath())

        assertFailsWith<ArtifactFileAccessException> {
            secure.sink("bad-\uD800.gguf", mustCreate = true)
        }

        secure.close()
    }

    @Test
    fun fifoSubstitutionFailsPromptlyAndDoesNotLeakDescriptors() = withRoots { root, _ ->
        if (System.getProperty("os.name").contains("win", ignoreCase = true)) return@withRoots
        val fifo = root.resolve("manifest")
        val mkfifo = ProcessBuilder("mkfifo", fifo.toString()).start()
        assertTrue(mkfifo.waitFor(5, TimeUnit.SECONDS) && mkfifo.exitValue() == 0)
        val secure = SecureArtifactRoot(root.toOkioPath())
        val completed = CountDownLatch(1)
        val observed = AtomicReference<ByteArray?>()
        Thread {
            observed.set(secure.readBounded("manifest", 1024))
            completed.countDown()
        }.apply { isDaemon = true }.start()

        assertTrue(completed.await(2, TimeUnit.SECONDS), "FIFO open blocked instead of failing closed")
        assertNull(observed.get())
        val before = openDescriptorCount()
        repeat(64) { assertNull(secure.readBounded("manifest", 1024)) }
        val after = openDescriptorCount()
        assertTrue(after <= before + 2, "native rejected-file descriptors leaked: before=$before after=$after")
        secure.close()
    }

    @Test
    fun descriptorRelativeRenameAndDeleteRemainInsidePinnedRoot() = withRoots { root, outside ->
        val secure = SecureArtifactRoot(root.toOkioPath())
        secure.sink("old.part", mustCreate = true).buffer().use { it.writeUtf8("safe") }

        secure.atomicMove("old.part", "published")
        secure.delete("published")

        assertFalse(root.resolve("old.part").exists())
        assertFalse(root.resolve("published").exists())
        assertFalse(outside.resolve("published").exists())
        secure.close()
    }
}

private fun openDescriptorCount(): Long = Files.list(java.nio.file.Path.of("/dev/fd")).use { it.count() }

private fun ByteArray.sha256HexForSecureRootTest(): String = okio.ByteString.of(*this).sha256().hex()

private fun withRoots(block: (java.nio.file.Path, java.nio.file.Path) -> Unit) {
    val base = Files.createTempDirectory("caraml-secure-root-test").toRealPath()
    val root = Files.createDirectory(base.resolve("root"))
    val outside = Files.createDirectory(base.resolve("outside"))
    try {
        block(root, outside)
    } finally {
        base.toFile().deleteRecursively()
    }
}
