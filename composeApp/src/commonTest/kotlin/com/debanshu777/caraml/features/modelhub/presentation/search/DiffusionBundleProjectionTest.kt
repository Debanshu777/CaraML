package com.debanshu777.caraml.features.modelhub.presentation.search

import com.debanshu777.huggingfacemanager.download.ArtifactManifest
import com.debanshu777.huggingfacemanager.download.ArtifactManifestEntry
import com.debanshu777.huggingfacemanager.download.DownloadArtifactIdentity
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiffusionBundleProjectionTest {
    @Test
    fun aggregateManifestMarksOnlyTheExactlyCommittedVariant() {
        val committed = identity("model.fp16.safetensors")
        val otherVariant = identity("model.safetensors")
        val entry = requireNotNull(
            ArtifactManifestEntry.create(
                logicalRole = "model",
                identity = committed,
                byteCount = committed.expectedBytes,
                contentSha256 = "c".repeat(64),
                localRelativePath = committed.relativePath,
            ),
        )
        val manifest = requireNotNull(ArtifactManifest.create(listOf(entry)))
        val variants = listOf(ui(committed), ui(otherVariant))

        val projected = projectCommittedDiffusionVariants(variants, manifest)

        assertTrue(projected.single { it.artifact == committed }.isDownloaded)
        assertFalse(projected.single { it.artifact == otherVariant }.isDownloaded)
    }

    @Test
    fun crashBeforeAggregateDoesNotPromoteVariantsFromLegacySyntheticRoomRow() {
        val variants = listOf(ui(identity("model.fp16.safetensors")), ui(identity("model.safetensors")))

        val projected = projectCommittedDiffusionVariants(
            variants = variants,
            manifest = null,
        )

        assertTrue(projected.none(GgufFileUiState::isDownloaded))
    }

    @Test
    fun crashBeforeAggregateDoesNotInferImmutableIdentityFromRoomFilename() {
        val committedPath = "model.fp16.safetensors"
        val variants = listOf(ui(identity(committedPath)), ui(identity("model.safetensors")))

        val projected = projectCommittedDiffusionVariants(
            variants = variants,
            manifest = null,
        )

        assertTrue(projected.none(GgufFileUiState::isDownloaded))
    }

    private fun identity(path: String) = requireNotNull(
        DownloadArtifactIdentity.create(
            repositoryId = "org/model",
            immutableRevision = "a".repeat(40),
            relativePath = path,
            remoteObjectId = null,
            expectedBytes = 1,
        ),
    )

    private fun ui(identity: DownloadArtifactIdentity) = GgufFileUiState(
        path = identity.relativePath,
        filename = identity.relativePath,
        sizeBytes = identity.expectedBytes,
        isDownloaded = false,
        progress = 42f,
        artifact = identity,
    )
}
