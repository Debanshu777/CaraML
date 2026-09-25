package com.debanshu777.huggingfacemanager.download

data class DownloadMetadataDTO(
    val artifact: DownloadArtifactIdentity,
    val logicalRole: String,
    val sizeBytes: Long?,
    val author: String?,
    val libraryName: String?,
    val pipelineTag: String?,
    val contextLength: Int? = null,
    val bundleId: String = requireNotNull(artifactBundleId(listOf(artifact))),
    val destinationRelativePath: String = immutableArtifactStorageLocation(artifact, bundleId).localRelativePath,
) {
    val layoutRelativePath: String
        get() = requireNotNull(
            persistedArtifactStorageLocation(artifact, bundleId, destinationRelativePath),
        ).layoutRelativePath

    val generationRootRelativePath: String?
        get() = requireNotNull(
            persistedArtifactStorageLocation(artifact, bundleId, destinationRelativePath),
        ).generationRootRelativePath

    val usesImmutableStorageLayout: Boolean
        get() = generationRootRelativePath != null

    init {
        require(
            logicalRole.isNotEmpty() && logicalRole == logicalRole.trim() && logicalRole.length <= 64 &&
                logicalRole.all { it.isLetterOrDigit() || it in "._-" },
        ) { "Invalid artifact role" }
        require(sizeBytes == null || sizeBytes == artifact.expectedBytes) { "Invalid artifact size" }
        require(bundleId.length == 64 && bundleId.all(::isAsciiHexDigit)) { "Invalid artifact bundle" }
        require(
            persistedArtifactStorageLocation(artifact, bundleId, destinationRelativePath) != null,
        ) { "Invalid artifact destination" }
    }
}
