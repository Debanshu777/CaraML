package com.debanshu777.huggingfacemanager.download

data class DownloadMetadataDTO(
    val artifact: DownloadArtifactIdentity,
    val logicalRole: String,
    val sizeBytes: Long?,
    val author: String?,
    val libraryName: String?,
    val pipelineTag: String?,
    val contextLength: Int? = null,
) {
    init {
        require(
            logicalRole.isNotEmpty() && logicalRole == logicalRole.trim() && logicalRole.length <= 64 &&
                logicalRole.all { it.isLetterOrDigit() || it in "._-" },
        ) { "Invalid artifact role" }
        require(sizeBytes == null || sizeBytes == artifact.expectedBytes) { "Invalid artifact size" }
    }
}
