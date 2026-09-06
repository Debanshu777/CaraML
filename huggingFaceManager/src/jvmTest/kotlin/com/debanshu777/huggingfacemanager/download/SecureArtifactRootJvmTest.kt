package com.debanshu777.huggingfacemanager.download

import okio.buffer
import okio.Path.Companion.toOkioPath
import java.nio.file.Files
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
}

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
