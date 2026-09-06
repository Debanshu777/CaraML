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
        metadata: DownloadMetadataDTO
    ): Flow<DownloadProgressDTO> {
        val request = validateDownloadArguments(modelId, path, metadata)
        val dirPath = pathProvider.getModelsStorageDirectory(request.modelId).trimEnd('/')
        val filePath = "$dirPath/${request.relativePath}"
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
        ).flowOn(Dispatchers.Default)
    }
}

private fun isPathWithinRoot(root: String, target: String): Boolean {
    val normalizedRoot = root.trimEnd('/')
    val normalizedTarget = target.trimEnd('/')
    return normalizedTarget != normalizedRoot && normalizedTarget.startsWith("$normalizedRoot/")
}
