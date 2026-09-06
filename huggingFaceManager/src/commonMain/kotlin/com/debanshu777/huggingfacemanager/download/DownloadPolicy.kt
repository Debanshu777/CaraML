package com.debanshu777.huggingfacemanager.download

private const val MAX_MODEL_ID_LENGTH = 193
private const val MAX_REPOSITORY_SEGMENT_LENGTH = 96
private const val MAX_RELATIVE_PATH_LENGTH = 1_024
private const val MAX_PATH_SEGMENT_LENGTH = 255
private const val UNKNOWN_LENGTH_PROGRESS_INTERVAL_BYTES = 1_048_576L

internal data class ValidatedDownloadRequest(
    val modelId: String,
    val relativePath: String,
)

internal fun validateDownloadRequest(modelId: String, path: String): ValidatedDownloadRequest =
    ValidatedDownloadRequest(
        modelId = validateModelId(modelId),
        relativePath = validateRelativeModelPath(path),
    )

internal fun validateDownloadArguments(
    modelId: String,
    path: String,
    metadata: DownloadMetadataDTO,
): ValidatedDownloadRequest {
    val request = validateDownloadRequest(modelId, path)
    val identity = metadata.artifact
    require(request.modelId == identity.repositoryId && request.relativePath == identity.relativePath) {
        "Download target does not match its immutable identity"
    }
    require(metadata.sizeBytes == null || metadata.sizeBytes == identity.expectedBytes) {
        "Download size does not match its immutable identity"
    }
    return request
}

internal fun validateModelId(modelId: String): String {
    require(modelId.isNotEmpty() && modelId == modelId.trim()) {
        "Invalid model identifier"
    }
    require(modelId.length <= MAX_MODEL_ID_LENGTH && '\\' !in modelId) {
        "Invalid model identifier"
    }

    val segments = modelId.split('/')
    require(segments.size in 1..2 && segments.all(::isSafeRepositorySegment)) {
        "Invalid model identifier"
    }
    return modelId
}

private fun isSafeRepositorySegment(segment: String): Boolean {
    if (segment.isEmpty() || segment.length > MAX_REPOSITORY_SEGMENT_LENGTH) return false
    if (segment == "." || segment == ".." || ".." in segment || "--" in segment) return false
    if (segment.first() == '.' || segment.first() == '-') return false
    if (segment.last() == '.' || segment.last() == '-') return false
    return segment.all { char ->
        char.isLetterOrDigit() || char == '_' || char == '-' || char == '.'
    }
}

private fun validateRelativeModelPath(path: String): String {
    require(path.isNotEmpty() && path == path.trim()) { "Invalid model file path" }
    require(path.length <= MAX_RELATIVE_PATH_LENGTH) { "Invalid model file path" }
    require(path.hasWellFormedUtf16()) { "Invalid model file path" }
    require(!path.startsWith('/') && !path.startsWith('\\') && '\\' !in path) {
        "Invalid model file path"
    }

    val segments = path.split('/')
    require(segments.all(::isSafePathSegment)) { "Invalid model file path" }
    return path
}

private fun isSafePathSegment(segment: String): Boolean {
    if (segment.isEmpty() || segment == "." || segment == "..") return false
    if (segment.length > MAX_PATH_SEGMENT_LENGTH) return false
    return segment.none { char ->
        char.code < 32 || char.code == 127 || char in ":*?\"<>|"
    }
}

private fun String.hasWellFormedUtf16(): Boolean {
    var index = 0
    while (index < length) {
        val current = this[index]
        when {
            current.isHighSurrogate() -> {
                if (index + 1 >= length || !this[index + 1].isLowSurrogate()) return false
                index += 2
            }
            current.isLowSurrogate() -> return false
            else -> index += 1
        }
    }
    return true
}

internal class DownloadProgressTracker(
    contentLength: Long?,
) {
    private val totalBytes = contentLength?.takeIf { it > 0L }
    private var lastPercentageBucket = -1
    private var lastUnknownLengthBytes = 0L

    fun next(bytesReceived: Long): DownloadProgressDTO? {
        require(bytesReceived >= 0L) { "bytesReceived must not be negative" }

        val total = totalBytes
        if (total != null) {
            if (bytesReceived >= total) return null
            val percentageBucket = ((bytesReceived * 100L) / total)
                .coerceIn(0L, 99L)
                .toInt()
            if (percentageBucket <= lastPercentageBucket) return null
            lastPercentageBucket = percentageBucket
            return DownloadProgressDTO(
                bytesReceived = bytesReceived,
                contentLength = total,
                percentage = percentageBucket.toFloat(),
            )
        }

        if (bytesReceived - lastUnknownLengthBytes < UNKNOWN_LENGTH_PROGRESS_INTERVAL_BYTES) {
            return null
        }
        lastUnknownLengthBytes = bytesReceived
        return DownloadProgressDTO(
            bytesReceived = bytesReceived,
            contentLength = null,
            percentage = -1f,
        )
    }
}
