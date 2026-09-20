package com.debanshu777.huggingfacemanager.download

import com.debanshu777.huggingfacemanager.createPlatformHttpClient
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import okio.Path.Companion.toOkioPath
import java.io.File

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
        requireImmutableArtifactWriteMetadata(metadata)
        val request = validateDownloadArguments(modelId, path, metadata)
        val dirPath = pathProvider.getModelsStorageDirectory(request.modelId)
        val root = File(dirPath).toPath().toAbsolutePath().normalize()
        val file = root.resolve(metadata.destinationRelativePath).normalize()
        require(file != root && file.startsWith(root)) { "Invalid model file path" }
        return downloadArtifact(
            httpClient = httpClient,
            pathProvider = pathProvider,
            baseUrl = baseUrl,
            modelId = modelId,
            path = path,
            metadata = metadata,
            modelRoot = root.toOkioPath(),
            target = file.toOkioPath(),
            localPath = file.toString(),
            resumeMetadata = resumeMetadata,
        ).flowOn(Dispatchers.IO)
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
