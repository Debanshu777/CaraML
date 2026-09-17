package com.debanshu777.huggingfacemanager.download

import com.debanshu777.huggingfacemanager.createPlatformHttpClient
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import okio.Path.Companion.toPath


actual class DownloadManager actual constructor(
    private val pathProvider: StoragePathProvider,
    private val baseUrl: String
) {
    private val httpClient = createPlatformHttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = 300_000L
            connectTimeoutMillis = 30_000L
            socketTimeoutMillis = 300_000L
        }
    }

    actual fun download(
        modelId: String,
        path: String,
        metadata: DownloadMetadataDTO,
        resumeMetadata: DownloadResumeMetadata?,
    ): Flow<DownloadProgressDTO> {
        val request = validateDownloadArguments(modelId, path, metadata)
        val dirPath = pathProvider.getModelsStorageDirectory(request.modelId).trimEnd('/')
        val filePath = "$dirPath/${metadata.destinationRelativePath}"
        require(isPathWithinRoot(dirPath, filePath)) { "Invalid model file path" }
        return downloadArtifact(
            httpClient,
            pathProvider,
            baseUrl,
            modelId,
            path,
            metadata,
            dirPath.toPath(normalize = true),
            filePath.toPath(normalize = true),
            filePath,
            resumeMetadata,
        ).flowOn(Dispatchers.Default)
    }

    actual suspend fun publishBundle(ownerModelId: String, artifacts: List<DownloadMetadataDTO>): Boolean =
        publishArtifactBundle(pathProvider, ownerModelId, artifacts)

    actual suspend fun validateBundle(ownerModelId: String, artifacts: List<DownloadMetadataDTO>): Boolean =
        validateArtifactBundle(pathProvider, ownerModelId, artifacts)

    actual suspend fun validatedBundle(ownerModelId: String): ArtifactManifest? =
        readValidatedArtifactBundle(pathProvider, ownerModelId)

    actual suspend fun validatedArtifacts(modelId: String): ArtifactManifest? =
        readValidatedArtifactManifest(pathProvider, modelId)

    actual suspend fun discardCheckpoint(metadata: DownloadMetadataDTO) =
        discardArtifactCheckpoint(pathProvider, metadata)

    actual suspend fun isPublished(metadata: DownloadMetadataDTO): Boolean =
        isArtifactPublished(pathProvider, metadata)
}

private fun isPathWithinRoot(root: String, target: String): Boolean {
    val normalizedRoot = root.trimEnd('/')
    val normalizedTarget = target.trimEnd('/')
    return normalizedTarget != normalizedRoot && normalizedTarget.startsWith("$normalizedRoot/")
}
