package com.debanshu777.huggingfacemanager.download

import com.debanshu777.huggingfacemanager.createPlatformHttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.FileOutputStream

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
    ): Flow<DownloadProgressDTO> = channelFlow {
        val relativePath = path.trim().replace('\\', '/').trimStart('/')
        val dirPath = pathProvider.getModelsStorageDirectory(modelId)
        val file = File(dirPath, relativePath)
        file.parentFile?.mkdirs()

        val requiredBytes = metadata.sizeBytes
        if (requiredBytes != null && requiredBytes > 0L) {
            val availableBytes = pathProvider.getAvailableStorageBytes()
            if (availableBytes < requiredBytes) {
                throw InsufficientStorageException(requiredBytes, availableBytes)
            }
        }

        val url = "$baseUrl/$modelId/resolve/main/$path?download=true"

        try {
            httpClient.prepareGet(url).execute { response ->
                val contentLength = response.headers["Content-Length"]?.toLongOrNull()
                val channel = response.bodyAsChannel()
                val buffer = ByteArray(8192)
                var bytesReceived = 0L

                FileOutputStream(file).use { output ->
                    while (true) {
                        val n = channel.readAvailable(buffer)
                        if (n <= 0) break
                        output.write(buffer, 0, n)
                        bytesReceived += n
                        val pct = if (contentLength != null && contentLength > 0)
                            (bytesReceived.toFloat() / contentLength * 100f).coerceIn(0f, 100f)
                        else -1f
                        send(DownloadProgressDTO(bytesReceived, contentLength, pct))
                    }
                }
                // Emit final progress with localPath set
                send(
                    DownloadProgressDTO(
                        bytesReceived = bytesReceived,
                        contentLength = contentLength,
                        percentage = 100f,
                        localPath = file.absolutePath
                    )
                )
            }
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }
        .flowOn(Dispatchers.IO)
}
