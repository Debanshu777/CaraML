package com.debanshu777.caraml.core.download

import com.debanshu777.caraml.core.recommendation.storage.EncodedModelEvidence
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
