package com.debanshu777.huggingfacemanager.download

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadMetadataDestinationTest {
    @Test
    fun newMetadataUsesExactBundleScopedStorageWithoutChangingNativeLayout() {
        val artifact = artifact(path = "unet/diffusion_pytorch_model.fp16.safetensors")
        val bundleId = requireNotNull(artifactBundleId(listOf(artifact)))

        val metadata = DownloadMetadataDTO(
            artifact = artifact,
            logicalRole = "model",
            sizeBytes = artifact.expectedBytes,
            author = null,
            libraryName = null,
            pipelineTag = null,
        )

        assertEquals(
            ".caraml-artifacts/$bundleId/unet/diffusion_pytorch_model.safetensors",
            metadata.destinationRelativePath,
        )
        assertEquals("unet/diffusion_pytorch_model.safetensors", metadata.layoutRelativePath)
        assertEquals(".caraml-artifacts/$bundleId", metadata.generationRootRelativePath)
        assertTrue(metadata.usesImmutableStorageLayout)
    }

    @Test
    fun persistedLegacyFp16MetadataRemainsReadableButIsNotWritableStorage() {
        val artifact = requireNotNull(
            DownloadArtifactIdentity.create(
                repositoryId = "org/model",
                immutableRevision = "a".repeat(40),
                relativePath = "unet/diffusion_pytorch_model.fp16.safetensors",
                remoteObjectId = null,
                expectedBytes = 1,
            ),
        )

        val metadata = metadata(artifact, "unet/diffusion_pytorch_model.safetensors")
        assertEquals("unet/diffusion_pytorch_model.safetensors", metadata.destinationRelativePath)
        assertFalse(metadata.usesImmutableStorageLayout)
    }

    @Test
    fun arbitraryRelocationIsRejectedBeforeStorageOrNetwork() {
        val artifact = requireNotNull(
            DownloadArtifactIdentity.create(
                repositoryId = "org/model",
                immutableRevision = "a".repeat(40),
                relativePath = "model.gguf",
                remoteObjectId = null,
                expectedBytes = 1,
            ),
        )

        assertFailsWith<IllegalArgumentException> { metadata(artifact, "relocated/model.gguf") }
    }

    @Test
    fun callerSuppliedDifferentBundleScopeIsRejected() {
        val artifact = artifact(path = "model.gguf")
        val wrongBundle = "f".repeat(64)

        assertFailsWith<IllegalArgumentException> {
            metadata(artifact, ".caraml-artifacts/$wrongBundle/model.gguf")
        }
    }

    @Test
    fun nonCanonicalBundleIdentityIsRejectedBeforeItCanAliasAStorageGeneration() {
        val artifact = artifact(path = "model.gguf")

        assertFailsWith<IllegalArgumentException> {
            DownloadMetadataDTO(
                artifact = artifact,
                logicalRole = "model",
                sizeBytes = artifact.expectedBytes,
                author = null,
                libraryName = null,
                pipelineTag = null,
                bundleId = "A".repeat(64),
            )
        }
    }

    @Test
    fun storagePrefixCannotPushDestinationBeyondBoundedRelativePath() {
        val segments = List(5) { "x".repeat(190) }
        val artifact = artifact(path = segments.joinToString("/") + "/model.gguf")

        assertFailsWith<IllegalArgumentException> {
            DownloadMetadataDTO(
                artifact = artifact,
                logicalRole = "model",
                sizeBytes = artifact.expectedBytes,
                author = null,
                libraryName = null,
                pipelineTag = null,
            )
        }
    }

    private fun metadata(artifact: DownloadArtifactIdentity, destination: String) = DownloadMetadataDTO(
        artifact = artifact,
        logicalRole = "model",
        sizeBytes = artifact.expectedBytes,
        author = null,
        libraryName = null,
        pipelineTag = null,
        destinationRelativePath = destination,
    )

    private fun artifact(path: String): DownloadArtifactIdentity = requireNotNull(
        DownloadArtifactIdentity.create(
            repositoryId = "org/model",
            immutableRevision = "a".repeat(40),
            relativePath = path,
            remoteObjectId = "sha256:${"b".repeat(64)}",
            expectedBytes = 1,
        ),
    )
}
