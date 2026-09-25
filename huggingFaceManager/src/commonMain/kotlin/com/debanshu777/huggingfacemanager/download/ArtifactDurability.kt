package com.debanshu777.huggingfacemanager.download

import okio.FileSystem
import okio.Path

interface ArtifactDurability {
    fun syncFile(relativePath: String)
    fun syncDirectory(relativePath: String)
}

class ArtifactDurabilityException : Exception("Artifact durability could not be established")

internal expect fun platformArtifactDurability(modelRoot: Path): ArtifactDurability

internal fun artifactDurability(
    modelRoot: Path,
    fileSystem: FileSystem,
): ArtifactDurability = if (fileSystem === FileSystem.SYSTEM) {
    platformArtifactDurability(modelRoot)
} else {
    object : ArtifactDurability {
        override fun syncFile(relativePath: String) {
            val path = modelRoot.resolveValidated(relativePath)
            val handle = fileSystem.openReadWrite(path, mustCreate = false, mustExist = true)
            try {
                handle.flush()
            } finally {
                handle.close()
            }
        }

        override fun syncDirectory(relativePath: String) = Unit
    }
}

internal fun Path.resolveValidated(relativePath: String): Path =
    if (relativePath == ".") this else this / relativePath

internal fun Path.relativeToRoot(root: Path): String? {
    val relative = runCatching { normalized().relativeTo(root.normalized()) }.getOrNull() ?: return null
    if (relative.toString() == ".") return "."
    if (relative.segments.any { it == "." || it == ".." }) return null
    return relative.segments.joinToString("/")
}
