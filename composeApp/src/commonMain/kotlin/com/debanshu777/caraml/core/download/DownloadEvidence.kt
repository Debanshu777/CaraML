package com.debanshu777.caraml.core.download

import com.debanshu777.caraml.core.recommendation.ModelFileIdentity
import com.debanshu777.caraml.core.recommendation.storage.EncodedModelEvidence
import com.debanshu777.caraml.core.recommendation.storage.PersistedModelEvidenceCodec
import com.debanshu777.huggingfacemanager.download.DownloadArtifactIdentity

internal fun pendingEvidence(identity: DownloadArtifactIdentity): EncodedModelEvidence =
    pendingEvidence(listOf(identity))

internal fun pendingEvidence(identities: Collection<DownloadArtifactIdentity>): EncodedModelEvidence =
    PersistedModelEvidenceCodec().encode(
        artifactIdentities = identities.map(DownloadArtifactIdentity::toModelFileIdentity),
        descriptor = null,
    )

internal fun DownloadArtifactIdentity.toModelFileIdentity(): ModelFileIdentity {
    val objectId = requireNotNull(remoteObjectId) { "Missing exact artifact object identity" }
    return ModelFileIdentity(
        repositoryId = repositoryId,
        revision = immutableRevision,
        path = relativePath,
        sizeBytes = expectedBytes,
        gitOid = objectId.takeIf { it.length == 40 && !it.startsWith("sha256:") },
        lfsOid = objectId.takeIf { it.startsWith("sha256:") },
        xetHash = objectId.takeIf { it.length != 40 && !it.startsWith("sha256:") },
        evidence = emptyList(),
    )
}
