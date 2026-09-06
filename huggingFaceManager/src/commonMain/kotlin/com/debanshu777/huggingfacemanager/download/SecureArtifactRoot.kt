package com.debanshu777.huggingfacemanager.download

import okio.Path
import okio.Sink

internal class ArtifactFileAccessException : Exception("Artifact storage is unavailable")

internal expect class SecureArtifactRoot(modelRoot: Path) {
    companion object {
        fun create(modelsRoot: Path, modelId: String): SecureArtifactRoot
    }

    fun createParentDirectories(relativePath: String)
    fun sink(relativePath: String, mustCreate: Boolean): Sink
    fun existsRegularFile(relativePath: String): Boolean
    fun size(relativePath: String): Long?
    fun readBounded(relativePath: String, maxBytes: Int): ByteArray?
    fun sha256(relativePath: String, expectedBytes: Long): String?
    fun atomicMove(sourceRelativePath: String, targetRelativePath: String)
    fun delete(relativePath: String)
    fun syncFile(relativePath: String)
    fun syncDirectory(relativePath: String)
    fun revalidate()
    fun close()
}
