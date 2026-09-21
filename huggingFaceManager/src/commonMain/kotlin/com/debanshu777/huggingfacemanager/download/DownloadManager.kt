package com.debanshu777.huggingfacemanager.download

import com.debanshu777.huggingfacemanager.HuggingFaceConstants
import com.debanshu777.huggingfacemanager.download.StoragePathProvider
import kotlinx.coroutines.flow.Flow

expect class DownloadManager(
    pathProvider: StoragePathProvider,
    baseUrl: String = HuggingFaceConstants.DEFAULT_BASE_URL
) {
    fun download(
        modelId: String,
        path: String,
        metadata: DownloadMetadataDTO,
        resumeMetadata: DownloadResumeMetadata? = null,
    ): Flow<DownloadProgressDTO>

    suspend fun publishBundle(ownerModelId: String, artifacts: List<DownloadMetadataDTO>): Boolean

    suspend fun validateBundle(ownerModelId: String, artifacts: List<DownloadMetadataDTO>): Boolean

    suspend fun validatedBundle(ownerModelId: String): ArtifactManifest?

    suspend fun validatedArtifacts(modelId: String): ArtifactManifest?

    suspend fun discardCheckpoint(metadata: DownloadMetadataDTO)

    suspend fun isPublished(metadata: DownloadMetadataDTO): Boolean

    suspend fun inspectStorage(artifacts: List<DownloadMetadataDTO>): List<DownloadArtifactStorageSnapshot>?

    suspend fun pendingBundleReplacement(
        ownerModelId: String,
        artifacts: List<DownloadMetadataDTO>,
    ): ArtifactManifest?

    suspend fun acknowledgeBundleReplacement(
        ownerModelId: String,
        artifacts: List<DownloadMetadataDTO>,
    ): Boolean
}

data class DownloadArtifactStorageSnapshot(
    val repositoryId: String,
    val destinationRelativePath: String,
    val targetBytes: Long?,
    val stagedBytes: Long?,
    val exactPublished: Boolean,
)
