package com.debanshu777.huggingfacemanager.download

data class DownloadProgressDTO(
    val bytesReceived: Long,
    val contentLength: Long?,
    val percentage: Float,
    val localPath: String? = null,
    val contentSha256: String? = null,
) {
    val bytesRemaining: Long? get() = contentLength?.let { (it - bytesReceived).coerceAtLeast(0) }
}
