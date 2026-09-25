package com.debanshu777.huggingfacemanager.download

data class DownloadResumeMetadata(
    val bytesReceived: Long,
    val entityTag: String?,
    val lastModified: String?,
) {
    init {
        require(bytesReceived > 0L)
        require(entityTag != null || lastModified != null)
        require(entityTag == null || entityTag.length <= 512 && entityTag.none(Char::isISOControl))
        require(lastModified == null || lastModified.length <= 128 && lastModified.none(Char::isISOControl))
    }
}
