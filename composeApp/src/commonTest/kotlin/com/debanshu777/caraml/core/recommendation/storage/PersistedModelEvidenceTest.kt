package com.debanshu777.caraml.core.recommendation.storage

import com.debanshu777.caraml.core.recommendation.AssessmentReason
import com.debanshu777.caraml.core.recommendation.Confidence
import com.debanshu777.caraml.core.recommendation.Evidence
import com.debanshu777.caraml.core.recommendation.LlmModelDescriptor
import com.debanshu777.caraml.core.recommendation.ModelFileIdentity
import com.debanshu777.caraml.core.recommendation.QuantizationEvidence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PersistedModelEvidenceTest {
    private val codec = PersistedModelEvidenceCodec()

    @Test
    fun completeLlmEvidenceRoundTrips() {
        val descriptor = llmDescriptor(path = "weights/model-q4_k_m.gguf")
        val encoded = codec.encode(descriptor.files, descriptor)

        val decoded = codec.decode(encoded)

        assertEquals(InstalledEvidenceState.COMPLETE, decoded.state)
        assertEquals(descriptor, decoded.descriptor)
        assertEquals(descriptor.files, decoded.artifactIdentities)
    }

    @Test
    fun pendingEvidenceRoundTripsWithoutClaimingRunnability() {
        val identity = modelIdentity(path = "weights/model.gguf")
        val decoded = codec.decode(codec.encode(listOf(identity), descriptor = null))

        assertEquals(InstalledEvidenceState.REQUIRES_ENRICHMENT, decoded.state)
        assertNull(decoded.descriptor)
        assertEquals(listOf(identity), decoded.artifactIdentities)
    }

    @Test
    fun digestMismatchIsRejected() {
        val valid = codec.encode(listOf(modelIdentity()), descriptor = null)

        assertFailsWith<IllegalArgumentException> {
            codec.decode(valid.copy(sha256 = "0".repeat(64)))
        }
    }

    @Test
    fun unknownFieldAndOversizedPayloadAreRejected() {
        val valid = codec.encode(listOf(modelIdentity()), descriptor = null)

        assertFailsWith<IllegalArgumentException> {
            codec.decode(valid.copy(payload = valid.payload.dropLast(1) + ",\"unknown\":true}"))
        }
        assertFailsWith<IllegalArgumentException> {
            codec.decode(valid.copy(payload = "x".repeat(262_145)))
        }
    }

    private fun llmDescriptor(path: String) = LlmModelDescriptor(
        repositoryId = "acme/model",
        revision = REVISION,
        file = modelIdentity(path),
        architecture = "llama",
        quantization = QuantizationEvidence.Known("Q4_K_M"),
        parameterCount = 7_000_000_000L,
        contextLimit = 8_192,
        transformerShape = null,
        ggufVersion = 3,
        requiredEngineFeatures = listOf("gguf"),
        evidence = listOf(Evidence(AssessmentReason.METADATA_VALIDATED, Confidence.HIGH, "fixture")),
    )

    private fun modelIdentity(path: String = "model.gguf") = ModelFileIdentity(
        repositoryId = "acme/model",
        revision = REVISION,
        path = path,
        sizeBytes = 1_024L,
        gitOid = "git-object",
        lfsOid = "sha256:lfs-object",
        xetHash = null,
        evidence = listOf(Evidence(AssessmentReason.METADATA_VALIDATED, Confidence.HIGH, "fixture")),
    )

    private companion object {
        const val REVISION = "0123456789abcdef0123456789abcdef01234567"
    }
}
