package com.debanshu777.huggingfacemanager.download

import com.debanshu777.huggingfacemanager.model.normalizedDiffusersRelativePath

data class DownloadMetadataDTO(
    val artifact: DownloadArtifactIdentity,
    val logicalRole: String,
    val sizeBytes: Long?,
    val author: String?,
    val libraryName: String?,
    val pipelineTag: String?,
    val contextLength: Int? = null,
    val destinationRelativePath: String = artifact.relativePath,
    val bundleId: String = requireNotNull(artifactBundleId(listOf(artifact))),
) {
    init {
        require(
            logicalRole.isNotEmpty() && logicalRole == logicalRole.trim() && logicalRole.length <= 64 &&
                logicalRole.all { it.isLetterOrDigit() || it in "._-" },
        ) { "Invalid artifact role" }
        require(sizeBytes == null || sizeBytes == artifact.expectedBytes) { "Invalid artifact size" }
        val destination = runCatching {
            validateDownloadRequest(artifact.repositoryId, destinationRelativePath).relativePath
        }.getOrNull()
        require(destination == destinationRelativePath) { "Invalid artifact destination" }
        require(destinationRelativePath == normalizedDiffusersRelativePath(artifact.relativePath)) {
            "Invalid artifact destination"
        }
        require(bundleId.length == 64 && bundleId.all(::isAsciiHexDigit)) { "Invalid artifact bundle" }
    }
}
