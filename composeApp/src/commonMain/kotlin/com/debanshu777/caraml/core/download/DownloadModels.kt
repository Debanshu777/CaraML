package com.debanshu777.caraml.core.download

import com.debanshu777.caraml.core.recommendation.storage.EncodedModelEvidence
import com.debanshu777.caraml.core.recommendation.storage.PersistedModelEvidenceCodec
import com.debanshu777.huggingfacemanager.download.DownloadMetadataDTO
import com.debanshu777.huggingfacemanager.download.artifactBundleId
import okio.Buffer

private const val MAX_BATCH_ARTIFACTS = 64
private const val MAX_DISPLAY_TEXT_LENGTH = 512
private const val MAX_MODEL_TYPE_LENGTH = 64

enum class DownloadBatchState {
    QUEUED,
    RUNNING,
    PAUSED,
    WAITING_FOR_NETWORK,
    VERIFYING,
    COMPLETED,
    FAILED_RETRYABLE,
    FAILED_TERMINAL,
    CANCELLED,
}

enum class DownloadArtifactState {
    QUEUED,
    RUNNING,
    PAUSED,
    WAITING_FOR_NETWORK,
    VERIFYING,
    COMPLETED,
    FAILED_RETRYABLE,
    FAILED_TERMINAL,
    CANCELLED,
}

enum class DownloadFailureCode {
    NETWORK,
    STORAGE,
    HTTP,
    INTEGRITY,
    SECURE_PATH,
    PLATFORM,
}

enum class DownloadUserIntent { RUN, PAUSE, CANCEL }

data class DownloadArtifactRequest(
    val metadata: DownloadMetadataDTO,
    val primary: Boolean,
)

data class DownloadBatchRequest(
    val ownerModelId: String,
    val modelType: String,
    val artifacts: List<DownloadArtifactRequest>,
    val evidence: EncodedModelEvidence,
    val downloadForLaterConfirmed: Boolean,
    val displayName: String,
) {
    init {
        require(ownerModelId.isBoundedDisplayText() && '/' in ownerModelId) { "Invalid owner model" }
        require(modelType.isNotBlank() && modelType.length <= MAX_MODEL_TYPE_LENGTH && modelType.none(Char::isISOControl)) {
            "Invalid model type"
        }
        require(displayName.isBoundedDisplayText()) { "Invalid display name" }
        require(artifacts.size in 1..MAX_BATCH_ARTIFACTS) { "Invalid artifact count" }
        require(artifacts.any(DownloadArtifactRequest::primary)) { "Missing primary artifact" }
        require(artifacts.filter(DownloadArtifactRequest::primary).all { it.metadata.artifact.repositoryId == ownerModelId }) {
            "Primary artifact does not belong to owner"
        }
        require(artifacts.all { it.metadata.usesImmutableStorageLayout }) { "Invalid artifact destination" }
        val expectedBundleId = requireNotNull(artifactBundleId(artifacts.map { it.metadata.artifact })) {
            "Invalid artifact bundle"
        }
        require(artifacts.all { it.metadata.bundleId == expectedBundleId }) { "Mismatched artifact bundle" }
        require(artifacts.map(::downloadArtifactTaskId).distinct().size == artifacts.size) { "Duplicate artifact" }
        require(
            artifacts.distinctBy {
                it.metadata.artifact.repositoryId to it.metadata.destinationRelativePath
            }.size == artifacts.size,
        ) { "Conflicting artifact destination" }
        PersistedModelEvidenceCodec().decode(evidence)
        artifacts.forEach { request ->
            require(request.metadata.sizeBytes == request.metadata.artifact.expectedBytes) { "Missing exact artifact size" }
            listOfNotNull(
                request.metadata.author,
                request.metadata.libraryName,
                request.metadata.pipelineTag,
            ).forEach { value -> require(value.isBoundedOptionalDisplayText()) { "Invalid display metadata" } }
        }
    }
}

data class DownloadArtifactSnapshot(
    val artifactId: String,
    val batchId: String,
    val request: DownloadArtifactRequest,
    val state: DownloadArtifactState,
    val userIntent: DownloadUserIntent,
    val bytesReceived: Long,
    val expectedBytes: Long,
    val entityTag: String? = null,
    val lastModified: String? = null,
    val platformTaskId: String? = null,
    val failureCode: DownloadFailureCode? = null,
    val retryCount: Int = 0,
)

data class DownloadBatchSnapshot(
    val batchId: String,
    val ownerModelId: String,
    val modelType: String,
    val displayName: String,
    val state: DownloadBatchState,
    val userIntent: DownloadUserIntent,
    val artifacts: List<DownloadArtifactSnapshot>,
    val evidence: EncodedModelEvidence,
    val failureCode: DownloadFailureCode? = null,
) {
    val bytesReceived: Long get() = artifacts.sumOf(DownloadArtifactSnapshot::bytesReceived)
    val expectedBytes: Long get() = artifacts.sumOf(DownloadArtifactSnapshot::expectedBytes)
}

fun downloadArtifactTaskId(request: DownloadArtifactRequest): String {
    val identity = request.metadata.artifact
    return canonicalSha256(
        identity.repositoryId,
        identity.immutableRevision,
        identity.relativePath,
        identity.remoteObjectId.orEmpty(),
        identity.expectedBytes.toString(),
        request.metadata.logicalRole,
        request.metadata.destinationRelativePath,
        request.metadata.bundleId,
        request.primary.toString(),
    )
}

internal fun downloadBatchArtifactId(batchId: String, request: DownloadArtifactRequest): String =
    canonicalSha256(batchId, downloadArtifactTaskId(request))

fun downloadBatchId(request: DownloadBatchRequest): String = canonicalSha256(
    request.ownerModelId,
    request.modelType,
    request.evidence.sha256,
    *request.artifacts.map(::downloadArtifactTaskId).sorted().toTypedArray(),
)

fun DownloadArtifactState.canTransitionTo(next: DownloadArtifactState): Boolean = next == this || next in when (this) {
    DownloadArtifactState.QUEUED -> setOf(
        DownloadArtifactState.RUNNING,
        DownloadArtifactState.PAUSED,
        DownloadArtifactState.FAILED_RETRYABLE,
        DownloadArtifactState.FAILED_TERMINAL,
        DownloadArtifactState.CANCELLED,
    )
    DownloadArtifactState.RUNNING -> setOf(
        DownloadArtifactState.PAUSED,
        DownloadArtifactState.WAITING_FOR_NETWORK,
        DownloadArtifactState.VERIFYING,
        DownloadArtifactState.FAILED_RETRYABLE,
        DownloadArtifactState.FAILED_TERMINAL,
        DownloadArtifactState.CANCELLED,
    )
    DownloadArtifactState.PAUSED -> setOf(DownloadArtifactState.QUEUED, DownloadArtifactState.CANCELLED)
    DownloadArtifactState.WAITING_FOR_NETWORK -> setOf(
        DownloadArtifactState.QUEUED,
        DownloadArtifactState.PAUSED,
        DownloadArtifactState.FAILED_RETRYABLE,
        DownloadArtifactState.FAILED_TERMINAL,
        DownloadArtifactState.CANCELLED,
    )
    DownloadArtifactState.VERIFYING -> setOf(
        DownloadArtifactState.COMPLETED,
        DownloadArtifactState.FAILED_RETRYABLE,
        DownloadArtifactState.FAILED_TERMINAL,
        DownloadArtifactState.CANCELLED,
    )
    DownloadArtifactState.FAILED_RETRYABLE -> setOf(DownloadArtifactState.QUEUED, DownloadArtifactState.CANCELLED)
    DownloadArtifactState.COMPLETED,
    DownloadArtifactState.FAILED_TERMINAL,
    DownloadArtifactState.CANCELLED,
    -> emptySet()
}

private fun canonicalSha256(vararg values: String): String {
    val buffer = Buffer()
    values.forEach { value ->
        val bytes = value.encodeToByteArray()
        buffer.writeInt(bytes.size)
        buffer.write(bytes)
    }
    return buffer.snapshot().sha256().hex()
}

private fun String.isBoundedDisplayText(): Boolean =
    isNotBlank() && this == trim() && length <= MAX_DISPLAY_TEXT_LENGTH && none(Char::isISOControl)

private fun String.isBoundedOptionalDisplayText(): Boolean =
    this == trim() && length <= MAX_DISPLAY_TEXT_LENGTH && none(Char::isISOControl)
