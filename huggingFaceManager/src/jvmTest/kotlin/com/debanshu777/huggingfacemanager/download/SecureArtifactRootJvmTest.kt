package com.debanshu777.huggingfacemanager.download

import okio.buffer
import okio.Path.Companion.toOkioPath
import java.nio.file.Files
import java.nio.file.SecureDirectoryStream
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SecureArtifactRootJvmTest {
    @Test
    fun symlinkedParentIsRejectedWithoutWritingOutsideThePinnedRoot() = withRoots { root, outside ->
        Files.createSymbolicLink(root.resolve("nested"), outside)
        val secure = secureOrVerifyUnavailable(root) ?: return@withRoots

        assertFailsWith<ArtifactFileAccessException> {
            secure.sink("nested/model.gguf.part", mustCreate = true).buffer().use { it.writeUtf8("blocked") }
        }

        assertFalse(outside.resolve("model.gguf.part").exists())
        secure.close()
    }

    @Test
    fun replacingTheRootPathAfterPinningFailsClosed() = withRoots { root, outside ->
        val secure = secureOrVerifyUnavailable(root) ?: return@withRoots
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
        val secure = secureOrVerifyUnavailable(root) ?: return@withRoots

        assertNull(secure.readBounded("manifest", 256 * 1024))

        secure.close()
    }

    @Test
    fun providerCapabilityDecisionFailsClosedWhenSecureDirectoryHandlesAreUnavailable() = withRoots { root, _ ->
        val providerSupportsPinnedDirectories = Files.newDirectoryStream(root).use {
            it is SecureDirectoryStream<java.nio.file.Path>
        }

        val constructionSucceeded = runCatching { SecureArtifactRoot(root.toOkioPath()).also { it.close() } }.isSuccess

        assertEquals(providerSupportsPinnedDirectories, constructionSucceeded)
    }
}

private fun secureOrVerifyUnavailable(root: java.nio.file.Path): SecureArtifactRoot? {
    val providerSupportsPinnedDirectories = Files.newDirectoryStream(root).use {
        it is SecureDirectoryStream<java.nio.file.Path>
    }
    return if (providerSupportsPinnedDirectories) {
        SecureArtifactRoot(root.toOkioPath())
    } else {
        assertFailsWith<ArtifactFileAccessException> { SecureArtifactRoot(root.toOkioPath()) }
        null
    }
}

private fun withRoots(block: (java.nio.file.Path, java.nio.file.Path) -> Unit) {
    val base = Files.createTempDirectory("caraml-secure-root-test")
    val root = Files.createDirectory(base.resolve("root"))
    val outside = Files.createDirectory(base.resolve("outside"))
    try {
        block(root, outside)
    } finally {
        base.toFile().deleteRecursively()
    }
}
