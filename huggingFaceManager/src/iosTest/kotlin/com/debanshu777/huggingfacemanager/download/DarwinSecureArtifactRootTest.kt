@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.debanshu777.huggingfacemanager.download

import okio.Path.Companion.toPath
import okio.buffer
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class DarwinSecureArtifactRootTest {
    @Test
    fun intermediateSymlinkIsRejected() = withDarwinRoots { base, outside ->
        val models = "$base/models"
        NSFileManager.defaultManager.createDirectoryAtPath(models, true, null, null)
        NSFileManager.defaultManager.createSymbolicLinkAtPath("$models/org", outside, null)

        assertFailsWith<ArtifactFileAccessException> {
            SecureArtifactRoot("$models/org/model".toPath())
        }
    }

    @Test
    fun rootReplacementAfterPinningCannotReceiveAWrite() = withDarwinRoots { base, outside ->
        val rootPath = "$base/models/org/model"
        NSFileManager.defaultManager.createDirectoryAtPath("$base/models", true, null, null)
        val secure = SecureArtifactRoot(rootPath.toPath())
        NSFileManager.defaultManager.moveItemAtPath(rootPath, "$base/pinned-moved", null)
        NSFileManager.defaultManager.createSymbolicLinkAtPath(rootPath, outside, null)

        assertFailsWith<ArtifactFileAccessException> {
            val sink = secure.sink("model.gguf.part", true).buffer()
            try {
                sink.writeUtf8("blocked")
            } finally {
                sink.close()
            }
        }
        assertFalse(NSFileManager.defaultManager.fileExistsAtPath("$outside/model.gguf.part"))
        secure.close()
    }
}

private fun withDarwinRoots(block: (base: String, outside: String) -> Unit) {
    val rawBase = "${NSTemporaryDirectory().trimEnd('/')}/caraml-secure-${NSUUID().UUIDString}"
    NSFileManager.defaultManager.createDirectoryAtPath("$rawBase/outside", true, null, null)
    val base = NSURL.fileURLWithPath(rawBase).URLByResolvingSymlinksInPath?.path ?: rawBase
    val outside = "$base/outside"
    try {
        block(base, outside)
    } finally {
        NSFileManager.defaultManager.removeItemAtPath(base, null)
    }
}
