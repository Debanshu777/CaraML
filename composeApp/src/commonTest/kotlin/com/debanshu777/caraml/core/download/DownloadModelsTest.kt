package com.debanshu777.caraml.core.download

import com.debanshu777.huggingfacemanager.download.DownloadArtifactIdentity
import com.debanshu777.huggingfacemanager.download.DownloadMetadataDTO
import com.debanshu777.huggingfacemanager.download.artifactBundleId
import com.debanshu777.huggingfacemanager.download.immutableArtifactStorageLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DownloadModelsTest {
    @Test
    fun batchIdIsOrderIndependentButRoleAndDestinationSensitive() {
        val model = artifactRequest(role = "model", path = "weights/model.gguf", primary = true)
        val encoder = artifactRequest(
            role = "clip-l",
            path = "text_encoder/model.safetensors",
            primary = false,
        )
        val first = batchRequest(listOf(model, encoder))
        val reversed = batchRequest(listOf(encoder, model))

        assertEquals(downloadBatchId(first), downloadBatchId(reversed))
        assertNotEquals(
            downloadArtifactTaskId(model),
            downloadArtifactTaskId(model.copy(metadata = model.metadata.copy(logicalRole = "draft-model"))),
        )
        assertEquals(64, downloadBatchId(first).length)
    }

    @Test
    fun requestRejectsDuplicateArtifactsAndMissingPrimary() {
        val artifact = artifactRequest(role = "model", path = "weights/model.gguf", primary = true)

        assertFailsWith<IllegalArgumentException> { batchRequest(listOf(artifact, artifact)) }
        assertFailsWith<IllegalArgumentException> {
            batchRequest(listOf(artifact.copy(primary = false)))
        }
    }

    @Test
    fun requestRejectsDistinctIdentitiesThatProjectToTheSamePhysicalDestination() {
        val identities = listOf(
            "unet/diffusion_pytorch_model.fp16.safetensors",
            "unet/diffusion_pytorch_model.safetensors",
        ).mapIndexed { index, path ->
            requireNotNull(
                DownloadArtifactIdentity.create(
                    repositoryId = "owner/model",
                    immutableRevision = (if (index == 0) "a" else "c").repeat(40),
                    relativePath = path,
                    remoteObjectId = (if (index == 0) "b" else "d").repeat(64),
                    expectedBytes = 1_024L,
                ),
            )
        }
        val bundleId = requireNotNull(artifactBundleId(identities))
        val requests = identities.mapIndexed { index, identity ->
            DownloadArtifactRequest(
                metadata = DownloadMetadataDTO(
                    artifact = identity,
                    logicalRole = if (index == 0) "model" else "alternate",
                    sizeBytes = identity.expectedBytes,
                    author = null,
                    libraryName = null,
                    pipelineTag = null,
                    bundleId = bundleId,
                ),
                primary = true,
            )
        }

        assertFailsWith<IllegalArgumentException> {
            DownloadBatchRequest(
                ownerModelId = "owner/model",
                modelType = "image",
                artifacts = requests,
                evidence = pendingEvidence(identities),
                downloadForLaterConfirmed = false,
                displayName = "Collision",
            )
        }
    }

    @Test
    fun requestRejectsCallerChosenBundleScopeEvenWhenDestinationMatchesThatScope() {
        val original = artifactRequest(role = "model", path = "weights/model.gguf", primary = true)
        val forgedBundleId = "f".repeat(64)
        val forged = original.copy(
            metadata = original.metadata.copy(
                bundleId = forgedBundleId,
                destinationRelativePath = immutableArtifactStorageLocation(
                    original.metadata.artifact,
                    forgedBundleId,
                ).localRelativePath,
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            DownloadBatchRequest(
                ownerModelId = "owner/model",
                modelType = "text",
                artifacts = listOf(forged),
                evidence = pendingEvidence(forged.metadata.artifact),
                downloadForLaterConfirmed = false,
                displayName = "Forged scope",
            )
        }
    }

    @Test
    fun requestRejectsLegacyUnscopedDestinationForANewDownloadBatch() {
        val scoped = artifactRequest(role = "model", path = "weights/model.gguf", primary = true)
        val legacy = scoped.copy(
            metadata = scoped.metadata.copy(
                destinationRelativePath = scoped.metadata.layoutRelativePath,
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            DownloadBatchRequest(
                ownerModelId = "owner/model",
                modelType = "text",
                artifacts = listOf(legacy),
                evidence = pendingEvidence(legacy.metadata.artifact),
                downloadForLaterConfirmed = false,
                displayName = "Legacy destination",
            )
        }
    }

    @Test
    fun advisoryDownloadForLaterFlagDoesNotChangeIdentityOrAdmission() {
        val artifact = artifactRequest(role = "model", path = "weights/model.gguf", primary = true)
        val immediate = batchRequest(listOf(artifact), downloadForLaterConfirmed = false)
        val confirmed = batchRequest(listOf(artifact), downloadForLaterConfirmed = true)

        assertEquals(downloadBatchId(immediate), downloadBatchId(confirmed))
        assertEquals(artifact, immediate.artifacts.single())
    }

    @Test
    fun completedArtifactCannotReturnToRunning() {
        assertFalse(DownloadArtifactState.COMPLETED.canTransitionTo(DownloadArtifactState.RUNNING))
        assertTrue(DownloadArtifactState.RUNNING.canTransitionTo(DownloadArtifactState.VERIFYING))
        assertTrue(DownloadArtifactState.FAILED_RETRYABLE.canTransitionTo(DownloadArtifactState.QUEUED))
        assertFalse(DownloadArtifactState.CANCELLED.canTransitionTo(DownloadArtifactState.QUEUED))
    }

    @Test
    fun displayMetadataRejectsControlCharactersAndUnboundedValues() {
        val artifact = artifactRequest(role = "model", path = "weights/model.gguf", primary = true)

        assertFailsWith<IllegalArgumentException> {
            batchRequest(listOf(artifact), displayName = "bad\nname")
        }
        assertFailsWith<IllegalArgumentException> {
            batchRequest(listOf(artifact), displayName = "x".repeat(513))
        }
    }

    @Test
    fun requestRejectsTamperedEvidenceDigest() {
        val artifact = artifactRequest(role = "model", path = "weights/model.gguf", primary = true)
        val request = batchRequest(listOf(artifact))

        assertFailsWith<IllegalArgumentException> {
            request.copy(evidence = request.evidence.copy(sha256 = "f".repeat(64)))
        }
    }

    @Test
    fun pendingEvidenceRejectsArtifactWithoutRemoteObjectIdentity() {
        val identity = requireNotNull(
            DownloadArtifactIdentity.create(
                repositoryId = "owner/model",
                immutableRevision = "a".repeat(40),
                relativePath = "weights/model.gguf",
                remoteObjectId = null,
                expectedBytes = 1_024L,
            ),
        )

        assertFailsWith<IllegalArgumentException> { pendingEvidence(identity) }
    }

    private fun batchRequest(
        artifacts: List<DownloadArtifactRequest>,
        downloadForLaterConfirmed: Boolean = false,
        displayName: String = "Example model",
    ): DownloadBatchRequest {
        val bundleId = requireNotNull(artifactBundleId(artifacts.map { it.metadata.artifact }))
        return DownloadBatchRequest(
            ownerModelId = "owner/model",
            modelType = "text",
            artifacts = artifacts.map { artifact ->
                artifact.copy(
                    metadata = artifact.metadata.copy(
                        bundleId = bundleId,
                        destinationRelativePath = immutableArtifactStorageLocation(
                            artifact.metadata.artifact,
                            bundleId,
                        ).localRelativePath,
                    ),
                )
            },
            evidence = pendingEvidence(artifacts.map { it.metadata.artifact }),
            downloadForLaterConfirmed = downloadForLaterConfirmed,
            displayName = displayName,
        )
    }

    private fun artifactRequest(
        role: String,
        path: String,
        primary: Boolean,
    ): DownloadArtifactRequest {
        val identity = requireNotNull(
            DownloadArtifactIdentity.create(
                repositoryId = "owner/model",
                immutableRevision = "a".repeat(40),
                relativePath = path,
                remoteObjectId = "b".repeat(64),
                expectedBytes = 1_024L,
            ),
        )
        return DownloadArtifactRequest(
            metadata = DownloadMetadataDTO(
                artifact = identity,
                logicalRole = role,
                sizeBytes = identity.expectedBytes,
                author = "owner",
                libraryName = "gguf",
                pipelineTag = "text-generation",
            ),
            primary = primary,
        )
    }
}
