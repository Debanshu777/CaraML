package com.debanshu777.huggingfacemanager.download

import com.debanshu777.huggingfacemanager.model.normalizedDiffusersRelativePath

private const val IMMUTABLE_ARTIFACT_DIRECTORY = ".caraml-artifacts"
private const val MAX_STORAGE_RELATIVE_PATH_LENGTH = 1_024

@ConsistentCopyVisibility
data class ImmutableArtifactStorageLocation internal constructor(
    val generationRootRelativePath: String?,
    val layoutRelativePath: String,
    val localRelativePath: String,
) {
    val isScoped: Boolean get() = generationRootRelativePath != null
}

/**
 * Derives the only storage location used for a new immutable download. Callers cannot provide a
 * filesystem path: the native layout comes from the validated remote identity and the generation
 * scope is the canonical exact-artifact bundle digest.
 */
fun immutableArtifactStorageLocation(
    identity: DownloadArtifactIdentity,
    bundleId: String,
): ImmutableArtifactStorageLocation {
    val generationRoot = immutableArtifactGenerationRoot(bundleId)
    val layout = normalizedDiffusersRelativePath(identity.relativePath)
    val local = "$generationRoot/$layout"
    require(local.length <= MAX_STORAGE_RELATIVE_PATH_LENGTH) { "Invalid artifact destination" }
    require(validateDownloadRequest(identity.repositoryId, local).relativePath == local) {
        "Invalid artifact destination"
    }
    return ImmutableArtifactStorageLocation(generationRoot, layout, local)
}

fun immutableArtifactGenerationRoot(bundleId: String): String {
    require(
        bundleId.length == 64 && bundleId == bundleId.lowercase() && bundleId.all(::isAsciiHexDigit),
    ) { "Invalid artifact bundle" }
    return "$IMMUTABLE_ARTIFACT_DIRECTORY/$bundleId"
}

/**
 * Validates a persisted destination. The unscoped form is accepted only for pre-migration
 * manifest/database rows; new metadata defaults exclusively to [immutableArtifactStorageLocation].
 */
fun persistedArtifactStorageLocation(
    identity: DownloadArtifactIdentity,
    bundleId: String,
    localRelativePath: String,
): ImmutableArtifactStorageLocation? {
    val scoped = runCatching { immutableArtifactStorageLocation(identity, bundleId) }.getOrNull()
        ?: return null
    return when (localRelativePath) {
        scoped.localRelativePath -> scoped
        scoped.layoutRelativePath -> ImmutableArtifactStorageLocation(
            generationRootRelativePath = null,
            layoutRelativePath = scoped.layoutRelativePath,
            localRelativePath = scoped.layoutRelativePath,
        )
        else -> null
    }
}
