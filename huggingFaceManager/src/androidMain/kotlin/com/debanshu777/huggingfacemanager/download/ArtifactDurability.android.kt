package com.debanshu777.huggingfacemanager.download

import okio.Path
import java.nio.channels.FileChannel
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

internal actual fun platformArtifactDurability(modelRoot: Path): ArtifactDurability =
    AndroidArtifactDurability(modelRoot)

private class AndroidArtifactDurability(private val modelRoot: Path) : ArtifactDurability {
    override fun syncFile(relativePath: String) = sync(relativePath, directory = false)

    override fun syncDirectory(relativePath: String) = sync(relativePath, directory = true)

    private fun sync(relativePath: String, directory: Boolean) {
        try {
            val path = Paths.get(modelRoot.resolveValidated(relativePath).toString())
            FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                channel.force(true)
            }
        } catch (_: Exception) {
            throw ArtifactDurabilityException()
        }
    }
}
