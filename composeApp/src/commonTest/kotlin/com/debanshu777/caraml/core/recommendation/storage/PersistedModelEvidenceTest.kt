package com.debanshu777.caraml.core.recommendation.storage

import com.debanshu777.caraml.core.recommendation.AssessmentReason
import com.debanshu777.caraml.core.recommendation.Confidence
import com.debanshu777.caraml.core.recommendation.DiffusionComponentDescriptor
import com.debanshu777.caraml.core.recommendation.DiffusionMode
import com.debanshu777.caraml.core.recommendation.DiffusionModelDescriptor
import com.debanshu777.caraml.core.recommendation.Evidence
import com.debanshu777.caraml.core.recommendation.LlmModelDescriptor
import com.debanshu777.caraml.core.recommendation.ModelFileIdentity
import com.debanshu777.caraml.core.recommendation.QuantizationEvidence
import okio.Buffer
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
        val unknownFieldPayload = valid.payload.dropLast(1) + ",\"unknown\":true}"

        assertFailsWith<IllegalArgumentException> {
            codec.decode(valid.withPayload(unknownFieldPayload))
        }
        assertFailsWith<IllegalArgumentException> {
            codec.decode(valid.copy(payload = "x".repeat(262_145)))
        }
    }

    @Test
    fun cappedUtf8ByteCountAcceptsExactAsciiBoundaryAndRejectsTheNextByte() {
        val exactBoundary = "a".repeat(262_144)

        assertEquals(262_144, cappedUtf8ByteCount(exactBoundary, 262_144))
        assertEquals(
            UTF8_BYTE_COUNT_LIMIT_EXCEEDED,
            cappedUtf8ByteCount(exactBoundary + "b", 262_144),
        )
    }

    @Test
    fun cappedUtf8ByteCountUsesUtf8WidthForBmpCharacters() {
        assertEquals(6, cappedUtf8ByteCount("A\u00e9\u20ac", 6))
        assertEquals(UTF8_BYTE_COUNT_LIMIT_EXCEEDED, cappedUtf8ByteCount("A\u00e9\u20ac", 5))
    }

    @Test
    fun cappedUtf8ByteCountTreatsSupplementaryPairAsFourBytes() {
        val supplementary = "\ud83d\ude00"

        assertEquals(5, cappedUtf8ByteCount("A$supplementary", 5))
        assertEquals(
            UTF8_BYTE_COUNT_LIMIT_EXCEEDED,
            cappedUtf8ByteCount("A$supplementary", 4),
        )
    }

    @Test
    fun cappedUtf8ByteCountRejectsUnpairedSurrogates() {
        assertEquals(UTF8_BYTE_COUNT_MALFORMED, cappedUtf8ByteCount("\ud800", 262_144))
        assertEquals(UTF8_BYTE_COUNT_MALFORMED, cappedUtf8ByteCount("\udc00", 262_144))
        assertEquals(UTF8_BYTE_COUNT_MALFORMED, cappedUtf8ByteCount("\ud800A", 262_144))
    }

    @Test
    fun decodeAcceptsExactAsciiByteBoundaryButRejectsBoundaryPlusOneBeforeDigest() {
        val exactBoundary = " ".repeat(262_144)
        val invalidPayload = assertFailsWith<IllegalArgumentException> {
            codec.decode(
                EncodedModelEvidence(
                    state = InstalledEvidenceState.REQUIRES_ENRICHMENT,
                    schemaVersion = 1,
                    payload = exactBoundary,
                    sha256 = sha256(exactBoundary),
                ),
            )
        }
        val tooLarge = assertFailsWith<IllegalArgumentException> {
            codec.decode(
                EncodedModelEvidence(
                    state = InstalledEvidenceState.REQUIRES_ENRICHMENT,
                    schemaVersion = 1,
                    payload = exactBoundary + " ",
                    sha256 = "0".repeat(64),
                ),
            )
        }

        assertEquals("Invalid evidence payload", invalidPayload.message)
        assertEquals("Evidence payload is too large", tooLarge.message)
    }

    @Test
    fun decodeRejectsMalformedSurrogatesBeforeDigestWork() {
        listOf("\ud800", "\udc00", "A\ud800B").forEach { payload ->
            val error = assertFailsWith<IllegalArgumentException> {
                codec.decode(
                    EncodedModelEvidence(
                        state = InstalledEvidenceState.REQUIRES_ENRICHMENT,
                        schemaVersion = 1,
                        payload = payload,
                        sha256 = "0".repeat(64),
                    ),
                )
            }

            assertEquals("Invalid evidence payload", error.message)
        }
    }

    @Test
    fun cappedUtf8ByteCountRejectsHugeHostileInputsWithoutReportingAByteCount() {
        assertEquals(
            UTF8_BYTE_COUNT_LIMIT_EXCEEDED,
            cappedUtf8ByteCount("a".repeat(4_000_000), 64),
        )
        assertEquals(
            UTF8_BYTE_COUNT_LIMIT_EXCEEDED,
            cappedUtf8ByteCount("\u20ac".repeat(1_000_000), 64),
        )
    }

    @Test
    fun cappedUtf8ByteCountStopsAtLimitBeforeMalformedSuffix() {
        assertEquals(
            UTF8_BYTE_COUNT_LIMIT_EXCEEDED,
            cappedUtf8ByteCount("a".repeat(65) + "\ud800", 64),
        )
        assertEquals(
            UTF8_BYTE_COUNT_LIMIT_EXCEEDED,
            cappedUtf8ByteCount("\u20ac".repeat(22) + "\ud800", 64),
        )
    }

    @Test
    fun payloadSchemaAndTransportStateMismatchesAreRejected() {
        val valid = codec.encode(listOf(modelIdentity()), descriptor = null)
        val unsupportedSchema = valid.payload.replaceFirst("\"schemaVersion\":1", "\"schemaVersion\":2")

        assertFailsWith<IllegalArgumentException> {
            codec.decode(valid.withPayload(unsupportedSchema))
        }
        assertFailsWith<IllegalArgumentException> {
            codec.decode(valid.copy(state = InstalledEvidenceState.COMPLETE))
        }
    }

    @Test
    fun digestMustBeLowercaseSha256() {
        val valid = codec.encode(listOf(modelIdentity()), descriptor = null)

        assertFailsWith<IllegalArgumentException> {
            codec.decode(valid.copy(sha256 = "A".repeat(64)))
        }
    }

    @Test
    fun duplicateArtifactIdentityIsRejectedAfterPayloadVerification() {
        val valid = codec.encode(listOf(modelIdentity()), descriptor = null)
        val identityJson = valid.payload.substringAfter("\"artifactIdentities\":[").substringBefore("],\"descriptor\"")
        val duplicateIdentityPayload = valid.payload.replaceFirst(
            "\"artifactIdentities\":[$identityJson]",
            "\"artifactIdentities\":[$identityJson,$identityJson]",
        )

        assertFailsWith<IllegalArgumentException> {
            codec.decode(valid.withPayload(duplicateIdentityPayload))
        }
    }

    @Test
    fun absoluteArtifactPathIsRejectedAfterPayloadVerification() {
        val valid = codec.encode(listOf(modelIdentity()), descriptor = null)
        val absolutePathPayload = valid.payload.replaceFirst("\"path\":\"model.gguf\"", "\"path\":\"/model.gguf\"")

        assertFailsWith<IllegalArgumentException> {
            codec.decode(valid.withPayload(absolutePathPayload))
        }
    }

    @Test
    fun malformedArtifactRevisionIsRejectedAfterPayloadVerification() {
        val valid = codec.encode(listOf(modelIdentity()), descriptor = null)
        val malformedRevisionPayload = valid.payload.replaceFirst("\"revision\":\"$REVISION\"", "\"revision\":\"${"a".repeat(39)}\"")

        assertFailsWith<IllegalArgumentException> {
            codec.decode(valid.withPayload(malformedRevisionPayload))
        }
    }

    @Test
    fun c1ControlCharactersAndUriSchemesAreRejectedInArtifactIdentityFields() {
        val valid = codec.encode(listOf(modelIdentity()), descriptor = null)
        val c1ControlPayload = valid.payload.replaceFirst("\"gitOid\":\"git-object\"", "\"gitOid\":\"git\\u0085object\"")
        val fileUriPayload = valid.payload.replaceFirst("\"gitOid\":\"git-object\"", "\"gitOid\":\"file:/etc/passwd\"")
        val dataUriPayload = valid.payload.replaceFirst("\"gitOid\":\"git-object\"", "\"gitOid\":\"data:text/plain,x\"")

        assertFailsWith<IllegalArgumentException> {
            codec.decode(valid.withPayload(c1ControlPayload))
        }
        assertFailsWith<IllegalArgumentException> {
            codec.decode(valid.withPayload(fileUriPayload))
        }
        assertFailsWith<IllegalArgumentException> {
            codec.decode(valid.withPayload(dataUriPayload))
        }
    }

    @Test
    fun oversizedRequiredEngineFeaturesAreRejectedBeforeSetConversion() {
        val descriptor = llmDescriptor(path = "weights/model-q4_k_m.gguf")
        val valid = codec.encode(descriptor.files, descriptor)
        val oversizedFeatures = List(257) { "\"gguf\"" }.joinToString(prefix = "[", postfix = "]")
        val payload = valid.payload.replaceFirst("\"requiredEngineFeatures\":[\"gguf\"]", "\"requiredEngineFeatures\":$oversizedFeatures")

        assertFailsWith<IllegalArgumentException> {
            codec.decode(valid.withPayload(payload))
        }
    }

    @Test
    fun oversizedMixedQuantizationsAreRejectedBeforeSetConversion() {
        val descriptor = llmDescriptor(
            path = "weights/model-q4_k_m.gguf",
            quantization = QuantizationEvidence.Mixed(listOf("Q4_K_M", "Q8_0")),
        )
        val valid = codec.encode(descriptor.files, descriptor)
        val oversizedQuantizations = List(256) { "\"Q4_K_M\"" }.plus("\"Q8_0\"")
            .joinToString(prefix = "[", postfix = "]")
        val payload = valid.payload.replaceFirst(
            "\"quantizations\":[\"Q4_K_M\",\"Q8_0\"]",
            "\"quantizations\":$oversizedQuantizations",
        )

        assertFailsWith<IllegalArgumentException> {
            codec.decode(valid.withPayload(payload))
        }
    }

    @Test
    fun oversizedDiffusionQuantizationDistributionIsRejectedBeforeSetConversion() {
        val descriptor = diffusionDescriptor()
        val valid = codec.encode(descriptor.components.map { it.file }, descriptor)
        val oversizedDistribution = List(257) { "\"F16\"" }.joinToString(prefix = "[", postfix = "]")
        val payload = valid.payload.replaceFirst(
            "\"quantizationDistribution\":[\"F16\"]",
            "\"quantizationDistribution\":$oversizedDistribution",
        )

        assertFailsWith<IllegalArgumentException> {
            codec.decode(valid.withPayload(payload))
        }
    }

    @Test
    fun descriptorArtifactMismatchIsRejectedAfterPayloadVerification() {
        val descriptor = llmDescriptor(path = "weights/model-q4_k_m.gguf")
        val valid = codec.encode(descriptor.files, descriptor)
        val mismatchPayload = valid.payload.replaceFirst(
            "\"path\":\"weights/model-q4_k_m.gguf\"",
            "\"path\":\"weights/other-q4_k_m.gguf\"",
        )

        assertFailsWith<IllegalArgumentException> {
            codec.decode(valid.withPayload(mismatchPayload))
        }
    }

    @Test
    fun completeDiffusionEvidenceRoundTrips() {
        val descriptor = diffusionDescriptor()

        val decoded = codec.decode(codec.encode(descriptor.components.map { it.file }, descriptor))

        assertEquals(InstalledEvidenceState.COMPLETE, decoded.state)
        assertEquals(descriptor, decoded.descriptor)
        assertEquals(descriptor.components.map { it.file }, decoded.artifactIdentities)
    }

    private fun llmDescriptor(
        path: String,
        quantization: QuantizationEvidence = QuantizationEvidence.Known("Q4_K_M"),
    ) = LlmModelDescriptor(
        repositoryId = "acme/model",
        revision = REVISION,
        file = modelIdentity(path),
        architecture = "llama",
        quantization = quantization,
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

    private fun diffusionDescriptor() = DiffusionModelDescriptor(
        repositoryId = "acme/model",
        revision = REVISION,
        components = listOf(
            DiffusionComponentDescriptor(
                file = modelIdentity("weights/model-F16.safetensors"),
                role = null,
                required = true,
                isPrimary = true,
                quantization = QuantizationEvidence.Known("F16"),
            ),
        ),
        mode = DiffusionMode.IMAGE,
        family = "acme",
        architecture = null,
        width = 512,
        height = 512,
        quantizationDistribution = listOf("F16"),
        requiredComponentsPresent = true,
        requiredEngineFeatures = emptyList(),
        evidence = listOf(Evidence(AssessmentReason.METADATA_VALIDATED, Confidence.HIGH, "fixture")),
    )

    private fun EncodedModelEvidence.withPayload(payload: String) = copy(payload = payload, sha256 = sha256(payload))

    private fun sha256(payload: String): String = Buffer().writeUtf8(payload).snapshot().sha256().hex()

    private companion object {
        const val REVISION = "0123456789abcdef0123456789abcdef01234567"
    }
}
