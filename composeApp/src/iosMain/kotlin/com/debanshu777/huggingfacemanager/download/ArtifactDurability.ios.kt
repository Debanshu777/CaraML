package com.debanshu777.huggingfacemanager.download

import okio.Path
import platform.posix.O_CLOEXEC
import platform.posix.O_DIRECTORY
import platform.posix.O_NOFOLLOW
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.fcntl
import platform.posix.fsync
import platform.posix.open

internal actual fun platformArtifactDurability(modelRoot: Path): ArtifactDurability =
    DarwinArtifactDurability(modelRoot)

private class DarwinArtifactDurability(private val modelRoot: Path) : ArtifactDurability {
    override fun syncFile(relativePath: String) = sync(relativePath, directory = false)

    override fun syncDirectory(relativePath: String) = sync(relativePath, directory = true)

    private fun sync(relativePath: String, directory: Boolean) {
        val flags = O_RDONLY or O_CLOEXEC or O_NOFOLLOW or if (directory) O_DIRECTORY else 0
        val descriptor = open(modelRoot.resolveValidated(relativePath).toString(), flags)
        if (descriptor < 0) throw ArtifactDurabilityException()
        try {
            val result = if (directory) fsync(descriptor) else fcntl(descriptor, DARWIN_F_FULLFSYNC)
            if (result != 0) throw ArtifactDurabilityException()
        } finally {
            close(descriptor)
        }
    }
}

private const val DARWIN_F_FULLFSYNC = 51
