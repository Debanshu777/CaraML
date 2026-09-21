package com.debanshu777.caraml.core.download

import com.debanshu777.caraml.core.recommendation.AssessmentReason
import com.debanshu777.caraml.core.recommendation.Confidence
import com.debanshu777.caraml.core.recommendation.DiffusionComponentDescriptor
import com.debanshu777.caraml.core.recommendation.DiffusionMode
import com.debanshu777.caraml.core.recommendation.DiffusionModelDescriptor
import com.debanshu777.caraml.core.recommendation.Evidence
import com.debanshu777.caraml.core.recommendation.LlmModelDescriptor
import com.debanshu777.caraml.core.recommendation.ModelFileIdentity
import com.debanshu777.caraml.core.recommendation.QuantizationEvidence
import com.debanshu777.caraml.core.recommendation.storage.InstalledEvidenceState
import com.debanshu777.caraml.core.recommendation.storage.PersistedModelEvidenceCodec
import com.debanshu777.huggingfacemanager.download.DownloadArtifactIdentity
import com.debanshu777.huggingfacemanager.download.DownloadMetadataDTO
import com.debanshu777.huggingfacemanager.sdcpp.ComponentRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DownloadEvidenceFactoryTest {
    private val codec = PersistedModelEvidenceCodec()
    private val factory = DownloadEvidenceFactory(codec)

    @Test
    fun exactLanguageArtifactsRetainTheCompleteDescriptor() {
        val identity = modelIdentity("weights/model-Q4_K_M.gguf")
        val descriptor = languageDescriptor(listOf(identity))

        val decoded = codec.decode(factory.create(listOf(request(identity, primary = true)), descriptor))

        assertEquals(InstalledEvidenceState.COMPLETE, decoded.state)
        assertEquals(descriptor, decoded.descriptor)
        assertEquals(listOf(identity), decoded.artifactIdentities)
    }

    @Test
    fun absentDescriptorCreatesEnrichmentEvidenceWithoutBlockingExactArtifacts() {
        val identity = modelIdentity("weights/model-Q4_K_M.gguf")

        val decoded = codec.decode(factory.create(listOf(request(identity, primary = true)), descriptor = null))

        assertEquals(InstalledEvidenceState.REQUIRES_ENRICHMENT, decoded.state)
        assertNull(decoded.descriptor)
        assertEquals(listOf(identity.withoutEvidence()), decoded.artifactIdentities)
    }

    @Test
    fun descriptorWithAnExtraIdentityDowngradesToEnrichment() {
        val requested = modelIdentity("weights/model-Q4_K_M.gguf")
        val staleExtra = modelIdentity("weights/model-Q8_0.gguf", objectId = "c".repeat(64))
        val descriptor = languageDescriptor(listOf(requested, staleExtra))

        val decoded = codec.decode(factory.create(listOf(request(requested, primary = true)), descriptor))

        assertEquals(InstalledEvidenceState.REQUIRES_ENRICHMENT, decoded.state)
        assertNull(decoded.descriptor)
        assertEquals(listOf(requested.withoutEvidence()), decoded.artifactIdentities)
    }

    @Test
    fun artifactWithoutAnExactObjectIdentityIsRejectedInsteadOfDowngraded() {
        val identity = modelIdentity("weights/model-Q4_K_M.gguf")
        val inexactArtifact = requireNotNull(
            DownloadArtifactIdentity.create(
                repositoryId = identity.repositoryId,
                immutableRevision = identity.revision,
                relativePath = identity.path,
                remoteObjectId = null,
                expectedBytes = identity.sizeBytes,
            ),
        )
        val request = DownloadArtifactRequest(
            metadata = DownloadMetadataDTO(
                artifact = inexactArtifact,
                logicalRole = "model",
                sizeBytes = inexactArtifact.expectedBytes,
                author = null,
                libraryName = null,
                pipelineTag = null,
            ),
            primary = true,
        )

        assertFailsWith<IllegalArgumentException> {
            factory.create(listOf(request), descriptor = null)
        }
    }

    @Test
    fun exactDiffusionBundleRetainsEveryPrimaryAndComponentIdentity() {
        val primary = modelIdentity("unet/diffusion_pytorch_model.safetensors")
        val clip = modelIdentity("text_encoder_2/model.safetensors", objectId = "d".repeat(64))
        val descriptor = DiffusionModelDescriptor(
            repositoryId = REPOSITORY_ID,
            revision = REVISION,
            components = listOf(
                DiffusionComponentDescriptor(
                    file = primary,
                    role = null,
                    required = true,
                    isPrimary = true,
                    quantization = QuantizationEvidence.Known("F16"),
                ),
                DiffusionComponentDescriptor(
                    file = clip,
                    role = ComponentRole.CLIP_G,
                    required = true,
                    isPrimary = false,
                    quantization = QuantizationEvidence.Known("F16"),
                ),
            ),
            mode = DiffusionMode.IMAGE,
            family = "SDXL",
            architecture = null,
            width = 512,
            height = 512,
            quantizationDistribution = listOf("F16"),
            requiredComponentsPresent = true,
            requiredEngineFeatures = emptyList(),
            evidence = emptyList(),
        )

        val decoded = codec.decode(
            factory.create(
                listOf(request(primary, primary = true), request(clip, primary = false)),
                descriptor,
            ),
        )

        assertEquals(InstalledEvidenceState.COMPLETE, decoded.state)
        assertEquals(descriptor, decoded.descriptor)
        assertEquals(setOf(primary, clip), decoded.artifactIdentities.toSet())
    }

    private fun request(identity: ModelFileIdentity, primary: Boolean): DownloadArtifactRequest {
        val artifact = requireNotNull(
            DownloadArtifactIdentity.create(
                repositoryId = identity.repositoryId,
                immutableRevision = identity.revision,
                relativePath = identity.path,
                remoteObjectId = "sha256:${requireNotNull(identity.lfsOid)}",
                expectedBytes = identity.sizeBytes,
            ),
        )
        return DownloadArtifactRequest(
            metadata = DownloadMetadataDTO(
                artifact = artifact,
                logicalRole = if (primary) "model" else "clip_g",
                sizeBytes = artifact.expectedBytes,
                author = null,
                libraryName = null,
                pipelineTag = null,
            ),
            primary = primary,
        )
    }

    private fun languageDescriptor(files: List<ModelFileIdentity>) = LlmModelDescriptor(
        repositoryId = REPOSITORY_ID,
        revision = REVISION,
        files = files,
        architecture = "llama",
        quantization = QuantizationEvidence.Known("Q4_K_M"),
        parameterCount = 1_000_000L,
        contextLimit = 4_096,
        transformerShape = null,
        ggufVersion = 3,
        requiredEngineFeatures = emptyList(),
        evidence = emptyList(),
    )

    private fun modelIdentity(
        path: String,
        objectId: String = "b".repeat(64),
    ) = ModelFileIdentity(
        repositoryId = REPOSITORY_ID,
        revision = REVISION,
        path = path,
        sizeBytes = 100L,
        gitOid = null,
        lfsOid = objectId,
        xetHash = null,
        evidence = listOf(Evidence(AssessmentReason.METADATA_VALIDATED, Confidence.HIGH, "fixture")),
    )

    private fun ModelFileIdentity.withoutEvidence() = ModelFileIdentity(
        repositoryId = repositoryId,
        revision = revision,
        path = path,
        sizeBytes = sizeBytes,
        gitOid = gitOid,
        lfsOid = "sha256:${requireNotNull(lfsOid)}",
        xetHash = xetHash,
        evidence = emptyList(),
    )

    private companion object {
        const val REPOSITORY_ID = "acme/model"
        const val REVISION = "0123456789abcdef0123456789abcdef01234567"
    }
}
