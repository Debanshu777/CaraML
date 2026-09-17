package com.debanshu777.caraml.core.download

import com.debanshu777.huggingfacemanager.download.DownloadArtifactIdentity
import com.debanshu777.huggingfacemanager.download.DownloadMetadataDTO
import com.debanshu777.huggingfacemanager.download.artifactBundleId
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
                artifact.copy(metadata = artifact.metadata.copy(bundleId = bundleId))
            },
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
