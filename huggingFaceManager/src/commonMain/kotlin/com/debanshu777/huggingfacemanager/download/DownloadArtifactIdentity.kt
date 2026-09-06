package com.debanshu777.huggingfacemanager.download

import kotlinx.serialization.Serializable

private const val MIN_IMMUTABLE_REVISION_LENGTH = 40
private const val MAX_IMMUTABLE_REVISION_LENGTH = 64
private const val MAX_REMOTE_OBJECT_ID_LENGTH = 256
internal const val MAX_ARTIFACT_BYTES = 1L shl 50

@Serializable
@ConsistentCopyVisibility
data class DownloadArtifactIdentity private constructor(
    val repositoryId: String,
    val immutableRevision: String,
    val relativePath: String,
    val remoteObjectId: String?,
    val expectedBytes: Long,
) {
    init {
        require(isValid(repositoryId, immutableRevision, relativePath, remoteObjectId, expectedBytes)) {
            "Invalid download artifact identity"
        }
    }

    companion object {
        fun create(
            repositoryId: String,
            immutableRevision: String,
            relativePath: String,
            remoteObjectId: String?,
            expectedBytes: Long,
        ): DownloadArtifactIdentity? = if (
            isValid(repositoryId, immutableRevision, relativePath, remoteObjectId, expectedBytes)
        ) {
            DownloadArtifactIdentity(
                repositoryId = repositoryId,
                immutableRevision = immutableRevision.lowercase(),
                relativePath = relativePath,
                remoteObjectId = remoteObjectId?.lowercase(),
                expectedBytes = expectedBytes,
            )
        } else {
            null
        }

        private fun isValid(
            repositoryId: String,
            immutableRevision: String,
            relativePath: String,
            remoteObjectId: String?,
            expectedBytes: Long,
        ): Boolean {
            val request = runCatching { validateDownloadRequest(repositoryId, relativePath) }.getOrNull()
                ?: return false
            if (request.modelId != repositoryId || request.relativePath != relativePath) return false
            if (immutableRevision.length !in MIN_IMMUTABLE_REVISION_LENGTH..MAX_IMMUTABLE_REVISION_LENGTH ||
                !immutableRevision.all(::isAsciiHexDigit)
            ) {
                return false
            }
            if (expectedBytes !in 1L..MAX_ARTIFACT_BYTES) return false
            return remoteObjectId == null || isValidRemoteObjectId(remoteObjectId)
        }
    }
}

private fun isValidRemoteObjectId(value: String): Boolean {
    if (value.isEmpty() || value.length > MAX_REMOTE_OBJECT_ID_LENGTH || value != value.trim()) return false
    val digest = value.removePrefix("sha256:")
    return digest.length in 40..128 && digest.all(::isAsciiHexDigit)
}

internal fun DownloadArtifactIdentity.expectedSha256OrNull(): String? {
    val value = remoteObjectId ?: return null
    if (!value.startsWith("sha256:")) return null
    val digest = value.removePrefix("sha256:")
    return digest.takeIf { it.length == 64 && it.all(::isAsciiHexDigit) }?.lowercase()
}

internal fun isAsciiHexDigit(value: Char): Boolean =
    value in '0'..'9' || value in 'a'..'f' || value in 'A'..'F'
