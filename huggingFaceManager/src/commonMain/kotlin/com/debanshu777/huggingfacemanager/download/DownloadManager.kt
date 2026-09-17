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
}
