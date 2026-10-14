package com.debanshu777.huggingfacemanager.download

data class DownloadResumeMetadata(
    val bytesReceived: Long,
    val entityTag: String?,
    val lastModified: String?,
) {
    companion object {
        fun createOrNull(bytesReceived: Long, entityTag: String?, lastModified: String?): DownloadResumeMetadata? {
            if (bytesReceived <= 0L) return null
            val boundedTag = boundedDownloadValidator(entityTag, 512)
            val boundedModified = boundedDownloadValidator(lastModified, 128)
            if (boundedTag == null && boundedModified == null) return null
            return DownloadResumeMetadata(bytesReceived, boundedTag, boundedModified)
        }
    }

    init {
        require(bytesReceived > 0L)
        require(entityTag != null || lastModified != null)
        require(entityTag == null || entityTag.length <= 512 && entityTag.none(Char::isISOControl))
        require(lastModified == null || lastModified.length <= 128 && lastModified.none(Char::isISOControl))
    }
}

internal fun boundedDownloadValidator(value: String?, maximumLength: Int): String? =
    value?.takeIf { it.isNotBlank() && it.length <= maximumLength && it.none(Char::isISOControl) }
