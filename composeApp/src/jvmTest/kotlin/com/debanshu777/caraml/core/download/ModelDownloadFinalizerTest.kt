package com.debanshu777.caraml.core.download

import com.debanshu777.caraml.core.recommendation.DiffusionComponentDescriptor
import com.debanshu777.caraml.core.recommendation.DiffusionMode
import com.debanshu777.caraml.core.recommendation.DiffusionModelDescriptor
import com.debanshu777.caraml.core.recommendation.ModelFileIdentity
import com.debanshu777.caraml.core.recommendation.QuantizationEvidence
import com.debanshu777.caraml.core.recommendation.storage.EncodedModelEvidence
import com.debanshu777.caraml.core.recommendation.storage.PersistedModelEvidenceCodec
import com.debanshu777.huggingfacemanager.download.DownloadArtifactIdentity
import com.debanshu777.huggingfacemanager.download.ArtifactVerificationException
import com.debanshu777.huggingfacemanager.download.DownloadMetadataDTO
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ModelDownloadFinalizerTest {
    @Test
    fun aggregateManifestIsValidatedBeforeCatalogPublication() = runTest {
        val calls = mutableListOf<String>()
        val batch = finalizerBatch()
        val finalizer = finalizer(batch, calls)

        finalizer.finalize("batch")
        finalizer.finalize("batch")

        assertEquals(
            listOf(
                "manifest:publish", "manifest:validate", "catalog:1",
                "manifest:publish", "manifest:validate", "catalog:1",
            ),
            calls,
        )
    }

    @Test
    fun malformedEvidenceNeverPublishesReadyCatalogEntry() = runTest {
        val calls = mutableListOf<String>()
        val valid = finalizerBatch()
        val malformed = valid.evidence.copy(sha256 = "0".repeat(64))

        assertFailsWith<ArtifactVerificationException> {
            finalizer(valid.copy(evidence = malformed), calls).finalize("batch")
        }

        assertEquals(listOf("manifest:publish", "manifest:validate"), calls)
    }

    @Test
    fun evidenceArtifactMismatchNeverPublishesReadyCatalogEntry() = runTest {
        val calls = mutableListOf<String>()
        val mismatched = finalizerEvidence(modelPath = "other.gguf")

        assertFailsWith<ArtifactVerificationException> {
            finalizer(finalizerBatch(evidence = mismatched), calls).finalize("batch")
        }

        assertEquals(listOf("manifest:publish", "manifest:validate"), calls)
    }

    @Test
    fun nonCanonicalMatchingObjectIdNeverPublishesReadyCatalogEntry() = runTest {
        val batch = finalizerBatch()
        val identities = batch.artifacts.map { artifact ->
            val identity = artifact.request.metadata.artifact.toModelFileIdentity()
            if (identity.repositoryId == batch.ownerModelId) {
                ModelFileIdentity(
                    repositoryId = identity.repositoryId,
                    revision = identity.revision,
                    path = identity.path,
                    sizeBytes = identity.sizeBytes,
                    gitOid = null,
                    lfsOid = "c".repeat(64),
                    xetHash = artifact.request.metadata.artifact.remoteObjectId,
                    evidence = emptyList(),
                )
            } else {
                identity
            }
        }
        val conflicting = PersistedModelEvidenceCodec().encode(identities, descriptor = null)

        assertRejectedAfterManifest(batch.copy(evidence = conflicting))
    }

    @Test
    fun restoredSnapshotWithoutPrimaryNeverPublishesReadyCatalogEntry() = runTest {
        val batch = finalizerBatch()
        val withoutPrimary = batch.copy(
            artifacts = batch.artifacts.map { artifact ->
                artifact.copy(request = artifact.request.copy(primary = false))
            },
        )

        assertRejectedAfterManifest(withoutPrimary)
    }

    @Test
    fun restoredSnapshotWithPrimaryForDifferentOwnerNeverPublishesReadyCatalogEntry() = runTest {
        val batch = finalizerBatch().copy(ownerModelId = "other/owner")

        assertRejectedAfterManifest(batch)
    }

    @Test
    fun restoredSnapshotWithCompleteDescriptorForDifferentOwnerNeverPublishesReadyCatalogEntry() = runTest {
        val batch = finalizerBatch()
        val identities = batch.artifacts.map { it.request.metadata.artifact.toModelFileIdentity() }
        val ownerIdentity = identities.single { it.repositoryId == batch.ownerModelId }
        val componentIdentity = identities.single { it.repositoryId != batch.ownerModelId }
        val descriptor = DiffusionModelDescriptor(
            repositoryId = componentIdentity.repositoryId,
            revision = componentIdentity.revision,
            components = listOf(
                DiffusionComponentDescriptor(
                    file = componentIdentity,
                    role = null,
                    required = true,
                    isPrimary = true,
                    quantization = QuantizationEvidence.Unknown,
                ),
                DiffusionComponentDescriptor(
                    file = ownerIdentity,
                    role = null,
                    required = true,
                    isPrimary = false,
                    quantization = QuantizationEvidence.Unknown,
                ),
            ),
            mode = DiffusionMode.IMAGE,
            family = "test",
            quantizationDistribution = emptySet(),
            requiredComponentsPresent = true,
            requiredEngineFeatures = emptySet(),
            evidence = emptyList(),
        )
        val complete = PersistedModelEvidenceCodec().encode(identities, descriptor)

        assertRejectedAfterManifest(batch.copy(evidence = complete))
    }
}

private suspend fun assertRejectedAfterManifest(batch: DownloadBatchSnapshot) {
    val calls = mutableListOf<String>()

    assertFailsWith<ArtifactVerificationException> {
        finalizer(batch, calls).finalize(batch.batchId)
    }

    assertEquals(listOf("manifest:publish", "manifest:validate"), calls)
}

private fun finalizer(
    batch: DownloadBatchSnapshot,
    calls: MutableList<String>,
) = ModelDownloadFinalizer(
    store = FinalizerStore(batch),
    bundlePublisher = object : BundlePublisher {
        override suspend fun publish(ownerModelId: String, artifacts: List<DownloadMetadataDTO>) =
            true.also { calls += "manifest:publish" }

        override suspend fun validate(ownerModelId: String, artifacts: List<DownloadMetadataDTO>) =
            true.also { calls += "manifest:validate" }
    },
    catalogPublisher = ModelCatalogPublisher { snapshot, evidence ->
        assertEquals(snapshot.evidence, evidence)
        calls += "catalog:${snapshot.artifacts.count { !it.request.primary }}"
    },
)

private class FinalizerStore(private val batch: DownloadBatchSnapshot) : DownloadTaskStore {
    override suspend fun create(request: DownloadBatchRequest, nowEpochMs: Long) = batch.batchId
    override fun observeForModel(modelId: String) = flowOf(listOf(batch))
    override suspend fun getBatch(batchId: String) = batch
    override suspend fun recoverableBatches() = listOf(batch)
    override suspend fun claim(artifactId: String, owner: String, nowEpochMs: Long, expiresAtEpochMs: Long) = true
    override suspend fun updateProgress(artifactId: String, bytesReceived: Long, entityTag: String?, lastModified: String?, nowEpochMs: Long) = true
    override suspend fun transitionArtifact(artifactId: String, state: DownloadArtifactState, failureCode: DownloadFailureCode?, nowEpochMs: Long) = true
    override suspend fun setUserIntent(batchId: String, intent: DownloadUserIntent, nowEpochMs: Long) = true
    override suspend fun setPlatformTaskId(artifactId: String, platformTaskId: String?, nowEpochMs: Long) = true
    override suspend fun releaseLease(artifactId: String, owner: String, nowEpochMs: Long) = true
    override suspend fun clearAll() = Unit
}

private fun finalizerBatch(evidence: EncodedModelEvidence? = null): DownloadBatchSnapshot {
    fun artifact(repo: String, path: String, role: String, primary: Boolean): DownloadArtifactRequest {
        val identity = finalizerIdentity(repo, path)
        return DownloadArtifactRequest(
            DownloadMetadataDTO(identity, role, 10L, null, null, null, bundleId = "c".repeat(64)),
            primary,
        )
    }
    val requests = listOf(
        artifact("owner/model", "model.gguf", "model", true),
        artifact("owner/component", "clip.gguf", "clip", false),
    )
    return DownloadBatchSnapshot(
        "batch", "owner/model", "text", "Model", DownloadBatchState.VERIFYING, DownloadUserIntent.RUN,
        requests.mapIndexed { index, request ->
            DownloadArtifactSnapshot(
                "artifact-$index", "batch", request, DownloadArtifactState.VERIFYING,
                DownloadUserIntent.RUN, 10L, 10L,
            )
        },
        evidence ?: pendingEvidence(requests.map { it.metadata.artifact }),
    )
}

private fun finalizerEvidence(modelPath: String): EncodedModelEvidence = pendingEvidence(
    listOf(
        finalizerIdentity("owner/model", modelPath),
        finalizerIdentity("owner/component", "clip.gguf"),
    ),
)

private fun finalizerIdentity(repo: String, path: String): DownloadArtifactIdentity = requireNotNull(
    DownloadArtifactIdentity.create(repo, "a".repeat(40), path, "b".repeat(64), 10L),
)
