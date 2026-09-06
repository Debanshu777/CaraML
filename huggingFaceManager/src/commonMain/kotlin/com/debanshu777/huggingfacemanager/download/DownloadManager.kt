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
        metadata: DownloadMetadataDTO
    ): Flow<DownloadProgressDTO>

    fun publishBundle(ownerModelId: String, artifacts: List<DownloadMetadataDTO>): Boolean

    fun validateBundle(ownerModelId: String, artifacts: List<DownloadMetadataDTO>): Boolean
}
