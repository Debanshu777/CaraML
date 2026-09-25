package com.debanshu777.huggingfacemanager.download

import okio.Path.Companion.toOkioPath
import okio.buffer
import java.nio.file.Files

/** Minimal installed-image entry point used by the desktop packaging matrix. */
object ArtifactFsInstalledImageSmoke {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isEmpty()) { "Invalid smoke arguments" }
        val trustedParent = Files.createTempDirectory("caraml-artifact-smoke-").toRealPath()
        try {
            val root = SecureArtifactRoot.create(trustedParent.toOkioPath(), "smoke/model")
            try {
                val relativePath = "nested/model.gguf.part"
                val expected = "artifact-fs-smoke".encodeToByteArray()
                root.createParentDirectories(relativePath)
                root.sink(relativePath, mustCreate = true).buffer().use { it.write(expected) }
                root.syncFile(relativePath)
                root.syncDirectory("nested")
                check(root.size(relativePath) == expected.size.toLong())
                check(root.readBounded(relativePath, expected.size)?.contentEquals(expected) == true)
                root.revalidate()
            } finally {
                root.close()
            }
        } finally {
            trustedParent.toFile().deleteRecursively()
        }
    }
}
