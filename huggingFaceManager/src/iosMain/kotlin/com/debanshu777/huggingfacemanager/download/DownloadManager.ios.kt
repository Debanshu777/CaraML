package com.debanshu777.huggingfacemanager.download

import com.debanshu777.huggingfacemanager.download.StoragePathProvider
import com.debanshu777.huggingfacemanager.createPlatformHttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.plugins.HttpTimeout
import io.ktor.utils.io.readAvailable
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import platform.Foundation.NSData
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.closeFile
import platform.Foundation.create
import platform.Foundation.fileHandleForWritingAtPath
import platform.Foundation.writeData


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

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    actual fun download(
        modelId: String,
        path: String,
        metadata: DownloadMetadataDTO
    ): Flow<DownloadProgressDTO> = channelFlow {
        val filename = path.substringAfterLast('/').ifEmpty { path }
        val dirPath = pathProvider.getModelsStorageDirectory(modelId)
        val filePath = "$dirPath/$filename"
        
        val fileManager = NSFileManager.defaultManager
        fileManager.createDirectoryAtPath(dirPath, true, null, null)
        
        val requiredBytes = metadata.sizeBytes
        if (requiredBytes != null && requiredBytes > 0L) {
            val availableBytes = pathProvider.getAvailableStorageBytes()
            if (availableBytes < requiredBytes) {
                throw InsufficientStorageException(requiredBytes, availableBytes)
            }
        }

        val url = "$baseUrl/$modelId/resolve/main/$path?download=true"

        try {
            fileManager.createFileAtPath(filePath, null, null)
            val fileHandle = NSFileHandle.fileHandleForWritingAtPath(filePath)
            try {
                httpClient.prepareGet(url).execute { response ->
                    val contentLength = response.headers["Content-Length"]?.toLongOrNull()
                    val channel = response.bodyAsChannel()
                    val buffer = ByteArray(8192)
                    var bytesReceived = 0L

                    while (true) {
                        val n = channel.readAvailable(buffer)
                        if (n <= 0) break

                        val nsData = buffer.usePinned { pinned ->
                            NSData.create(bytes = pinned.addressOf(0), length = n.toULong())
                        }
                        fileHandle?.writeData(nsData)

                        bytesReceived += n
                        val pct = if (contentLength != null && contentLength > 0)
                            (bytesReceived.toFloat() / contentLength * 100f).coerceIn(0f, 100f)
                        else -1f
                        send(DownloadProgressDTO(bytesReceived, contentLength, pct))
                    }

                    // Emit final progress with localPath set
                    send(DownloadProgressDTO(
                        bytesReceived = bytesReceived,
                        contentLength = contentLength,
                        percentage = 100f,
                        localPath = filePath
                    ))
                }
            } finally {
                fileHandle?.closeFile()
            }
        } catch (e: Exception) {
            fileManager.removeItemAtPath(filePath, null)
            throw e
        }
    }
}
