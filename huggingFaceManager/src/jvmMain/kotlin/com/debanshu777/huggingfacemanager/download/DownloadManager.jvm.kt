package com.debanshu777.huggingfacemanager.download

import com.debanshu777.huggingfacemanager.createPlatformHttpClient
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import okio.Path.Companion.toPath
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
        metadata: DownloadMetadataDTO
    ): Flow<DownloadProgressDTO> {
        val request = validateDownloadArguments(modelId, path, metadata)
        val dirPath = pathProvider.getModelsStorageDirectory(request.modelId)
        val root = File(dirPath).canonicalFile
        val file = File(root, request.relativePath).canonicalFile
        require(isPathWithinRoot(root, file)) { "Invalid model file path" }
        return downloadArtifact(
            httpClient = httpClient,
            pathProvider = pathProvider,
            baseUrl = baseUrl,
            modelId = modelId,
            path = path,
            metadata = metadata,
            modelRoot = root.absolutePath.toPath(normalize = true),
            target = file.absolutePath.toPath(normalize = true),
            localPath = file.absolutePath,
        ).flowOn(Dispatchers.IO)
    }
}

private fun isPathWithinRoot(root: File, target: File): Boolean {
    if (target.path == root.path) return false
    return target.path.startsWith(root.path + File.separator)
}
