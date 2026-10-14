package com.debanshu777.huggingfacemanager.download

import okio.Path
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

internal actual fun platformArtifactDurability(modelRoot: Path): ArtifactDurability =
    JvmArtifactDurability(modelRoot)

private class JvmArtifactDurability(private val modelRoot: Path) : ArtifactDurability {
    override fun syncFile(relativePath: String) = force(relativePath, writable = true)

    override fun syncDirectory(relativePath: String) = force(relativePath, writable = false)

    private fun force(relativePath: String, writable: Boolean) {
        try {
            val options = if (writable) {
                arrayOf(StandardOpenOption.READ, StandardOpenOption.WRITE)
            } else {
                arrayOf(StandardOpenOption.READ)
            }
            FileChannel.open(modelRoot.resolveValidated(relativePath).toNioPath(), *options).use { channel ->
                channel.force(true)
            }
        } catch (_: Exception) {
            throw ArtifactDurabilityException()
        }
    }
}
