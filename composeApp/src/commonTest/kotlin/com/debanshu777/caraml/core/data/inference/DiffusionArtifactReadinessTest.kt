package com.debanshu777.caraml.core.data.inference

import com.debanshu777.huggingfacemanager.download.ArtifactManifest
import com.debanshu777.huggingfacemanager.download.ArtifactManifestEntry
import com.debanshu777.huggingfacemanager.download.DownloadArtifactIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DiffusionArtifactReadinessTest {
    @Test
    fun checkpointPrimaryWinsWithoutManifestDirectoryEvidence() {
        val manifest = manifest(
            artifact("checkpoint.safetensors", logicalRole = "model"),
        )

        assertEquals(
            VerifiedDiffusionLoadTarget.File("checkpoint.safetensors"),
            manifest.verifiedDiffusionLoadTarget(MODEL_ID),
        )
    }

    @Test
    fun directoryModeRequiresEveryPathConsumedByTheNativeLoader() {
        val incomplete = manifest(
            artifact("unet/diffusion_pytorch_model.safetensors", logicalRole = "model"),
            artifact("text_encoder/model.safetensors", logicalRole = "diffusers-clip-l"),
        )
        val complete = manifest(
            artifact("unet/diffusion_pytorch_model.safetensors", logicalRole = "model"),
            artifact("vae/diffusion_pytorch_model.safetensors", logicalRole = "diffusers-vae"),
            artifact("text_encoder/model.safetensors", logicalRole = "diffusers-clip-l"),
            artifact("text_encoder_2/model.safetensors", logicalRole = "diffusers-clip-g"),
        )

        assertNull(incomplete.verifiedDiffusionLoadTarget(MODEL_ID))
        assertEquals(
            VerifiedDiffusionLoadTarget.Directory,
            complete.verifiedDiffusionLoadTarget(MODEL_ID),
        )
    }

    private fun manifest(vararg entries: ArtifactManifestEntry): ArtifactManifest =
        requireNotNull(ArtifactManifest.create(entries.toList()))

    private fun artifact(path: String, logicalRole: String): ArtifactManifestEntry {
        val identity = requireNotNull(
            DownloadArtifactIdentity.create(
                repositoryId = MODEL_ID,
                immutableRevision = "a".repeat(40),
                relativePath = path,
                remoteObjectId = null,
                expectedBytes = 4L,
            ),
        )
        return requireNotNull(
            ArtifactManifestEntry.create(
                logicalRole = logicalRole,
                identity = identity,
                byteCount = 4L,
                contentSha256 = "c".repeat(64),
                bundleId = "b".repeat(64),
                localRelativePath = path,
            ),
        )
    }

    private companion object {
        const val MODEL_ID = "org/diffusion-model"
    }
}
