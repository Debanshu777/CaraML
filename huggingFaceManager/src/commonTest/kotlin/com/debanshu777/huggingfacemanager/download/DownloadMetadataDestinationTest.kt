package com.debanshu777.huggingfacemanager.download

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DownloadMetadataDestinationTest {
    @Test
    fun fp16ArtifactMustUseItsDescriptorDerivedNormalizedDestination() {
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

    private fun metadata(artifact: DownloadArtifactIdentity, destination: String) = DownloadMetadataDTO(
        artifact = artifact,
        logicalRole = "model",
        sizeBytes = artifact.expectedBytes,
        author = null,
        libraryName = null,
        pipelineTag = null,
        destinationRelativePath = destination,
    )
}
