package com.debanshu777.caraml.core.download

import com.debanshu777.caraml.core.recommendation.DiffusionModelDescriptor
import com.debanshu777.caraml.core.recommendation.LlmModelDescriptor
import com.debanshu777.caraml.core.recommendation.ModelDescriptor
import com.debanshu777.caraml.core.recommendation.ModelFileIdentity
import com.debanshu777.caraml.core.recommendation.canonicalDownloadRemoteObjectId
import com.debanshu777.caraml.core.recommendation.storage.EncodedModelEvidence
import com.debanshu777.caraml.core.recommendation.storage.PersistedModelEvidenceCodec

class DownloadEvidenceFactory(
    private val codec: PersistedModelEvidenceCodec,
) {
    fun create(
        artifacts: Collection<DownloadArtifactRequest>,
        descriptor: ModelDescriptor?,
    ): EncodedModelEvidence {
        val requestIdentities = artifacts.map { it.metadata.artifact.toModelFileIdentity() }
        val enrichmentEvidence = codec.encode(requestIdentities, descriptor = null)
        val descriptorIdentities = descriptor?.requiredIdentities() ?: return enrichmentEvidence
        if (!descriptorIdentities.haveSameExactIdentities(requestIdentities)) return enrichmentEvidence

        return try {
            codec.encode(descriptorIdentities, descriptor)
        } catch (_: IllegalArgumentException) {
            enrichmentEvidence
        }
    }
}

private fun ModelDescriptor.requiredIdentities(): List<ModelFileIdentity> = when (this) {
    is LlmModelDescriptor -> files
    is DiffusionModelDescriptor -> components.map { it.file }
}

private fun Collection<ModelFileIdentity>.haveSameExactIdentities(
    other: Collection<ModelFileIdentity>,
): Boolean = size == other.size && map(ModelFileIdentity::exactIdentity).toSet() ==
    other.map(ModelFileIdentity::exactIdentity).toSet()

private fun ModelFileIdentity.exactIdentity() = ExactModelFileIdentity(
    repositoryId = repositoryId,
    revision = revision.lowercase(),
    path = path,
    sizeBytes = sizeBytes,
    remoteObjectId = canonicalDownloadRemoteObjectId()?.lowercase(),
)

private data class ExactModelFileIdentity(
    val repositoryId: String,
    val revision: String,
    val path: String,
    val sizeBytes: Long,
    val remoteObjectId: String?,
)
