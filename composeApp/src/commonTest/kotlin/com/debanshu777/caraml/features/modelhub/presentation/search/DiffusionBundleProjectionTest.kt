package com.debanshu777.caraml.features.modelhub.presentation.search

import com.debanshu777.huggingfacemanager.download.ArtifactManifest
import com.debanshu777.huggingfacemanager.download.ArtifactManifestEntry
import com.debanshu777.huggingfacemanager.download.DownloadArtifactIdentity
import com.debanshu777.huggingfacemanager.download.DownloadMetadataDTO
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun interruptedBundleRecoveryIsIndependentOfTreeAndTriggerOrder() {
        val unet = ui(identity("unet/diffusion_pytorch_model.safetensors"))
        val vae = ui(identity("vae/diffusion_pytorch_model.safetensors"))
        val clip = ui(identity("text_encoder/model.safetensors"))
        val clipG = ui(identity("text_encoder_2/model.safetensors"))
        val component = metadata(clipG.artifact!!, "clip_g")

        val forward = buildDeterministicDiffusionBundleMetadata(
            selected = listOf(unet, vae, clip, clipG),
            componentMetadata = listOf(component),
            author = "author",
            libraryName = "library",
            pipelineTag = "text-to-image",
        )
        val reversed = buildDeterministicDiffusionBundleMetadata(
            selected = listOf(clipG, clip, vae, unet),
            componentMetadata = listOf(component),
            author = "author",
            libraryName = "library",
            pipelineTag = "text-to-image",
        )

        assertEquals(forward, reversed)
        assertEquals("model", forward.single { it.artifact == unet.artifact }.logicalRole)
        assertEquals("clip_g", forward.single { it.artifact == clipG.artifact }.logicalRole)

        val committed = forward.map { expected -> manifestEntry(expected) }
        committed.indices.forEach { lastCommitted ->
            val prefix = committed.take(lastCommitted + 1).reversed()
            val recovered = recoverInterruptedDiffusionBundle(
                candidates = listOf(reversed, forward).reversed(),
                installedEntries = prefix,
            )

            assertEquals(forward, recovered?.metadata)
            assertEquals(
                prefix.map { it.identity }.toSet(),
                recovered?.installedMetadata?.map { it.artifact }?.toSet(),
            )
        }

        val aggregate = requireNotNull(ArtifactManifest.create(committed))
        assertTrue(aggregate.matchesExactBundle(forward))
    }

    @Test
    fun partialProjectionRequiresRoleBundleDestinationAndIdentity() {
        val file = ui(identity("checkpoint.safetensors"))
        val expected = buildDeterministicDiffusionBundleMetadata(
            selected = listOf(file),
            componentMetadata = emptyList(),
            author = null,
            libraryName = null,
            pipelineTag = null,
        ).single()
        val exact = manifestEntry(expected)
        val wrongRole = requireNotNull(
            ArtifactManifestEntry.create(
                logicalRole = "wrong-role",
                identity = exact.identity,
                byteCount = exact.byteCount,
                contentSha256 = exact.contentSha256,
                bundleId = exact.bundleId,
                localRelativePath = exact.localRelativePath,
            ),
        )

        assertEquals(
            emptyList(),
            recoverInterruptedDiffusionBundle(listOf(listOf(expected)), listOf(wrongRole))
                ?.installedMetadata.orEmpty(),
        )
        assertEquals(
            listOf(expected),
            recoverInterruptedDiffusionBundle(listOf(listOf(expected)), listOf(exact))
                ?.installedMetadata,
        )
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

    private fun metadata(identity: DownloadArtifactIdentity, role: String) = DownloadMetadataDTO(
        artifact = identity,
        logicalRole = role,
        sizeBytes = identity.expectedBytes,
        author = null,
        libraryName = "stable-diffusion.cpp",
        pipelineTag = null,
        destinationRelativePath = identity.relativePath,
    )

    private fun manifestEntry(metadata: DownloadMetadataDTO) = requireNotNull(
        ArtifactManifestEntry.create(
            logicalRole = metadata.logicalRole,
            identity = metadata.artifact,
            byteCount = metadata.artifact.expectedBytes,
            contentSha256 = "c".repeat(64),
            bundleId = metadata.bundleId,
            localRelativePath = metadata.destinationRelativePath,
        ),
    )
}
