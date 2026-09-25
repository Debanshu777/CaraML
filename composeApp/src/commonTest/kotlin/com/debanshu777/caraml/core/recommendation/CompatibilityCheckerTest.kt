package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.platform.BackendCapability
import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.BackendStatus
import com.debanshu777.caraml.core.platform.HardwareProfile
import com.debanshu777.caraml.core.platform.MemoryTopology
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CompatibilityCheckerTest {
    @Test
    fun rejectsUnsupportedGgufVersionBeforeConsultingEngine() {
        var calls = 0
        val checker = CompatibilityChecker(EngineCapabilitySource {
            calls++
            SupportEvidence.Supported
        })

        val result = checker.check(llmDescriptor(ggufVersion = 1), hardwareProfile())

        assertIs<Compatibility.Incompatible>(result)
        assertTrue(AssessmentReason.UNSUPPORTED_GGUF_VERSION in result.reasons)
        assertEquals(0, calls)
    }

    @Test
    fun rejectsMissingRequiredDiffusionComponentBeforeConsultingEngine() {
        var calls = 0
        val checker = CompatibilityChecker(EngineCapabilitySource {
            calls++
            SupportEvidence.Supported
        })

        val result = checker.check(diffusionDescriptor(requiredComponentsPresent = false), hardwareProfile())

        assertIs<Compatibility.Incompatible>(result)
        assertTrue(AssessmentReason.MISSING_REQUIRED_COMPONENT in result.reasons)
        assertEquals(0, calls)
    }

    @Test
    fun preservesUnsupportedAndUnknownEngineEvidenceAsDistinctResults() {
        val unsupported = CompatibilityChecker(EngineCapabilitySource {
            SupportEvidence.Unsupported(
                reasons = listOf(AssessmentReason.UNSUPPORTED_ARCHITECTURE),
                evidence = listOf(Evidence(AssessmentReason.UNSUPPORTED_ARCHITECTURE, Confidence.HIGH)),
            )
        }).check(llmDescriptor(), hardwareProfile())
        assertIs<Compatibility.Incompatible>(unsupported)

        val unknown = CompatibilityChecker(UnknownEngineCapabilitySource)
            .check(llmDescriptor(), hardwareProfile())
        assertIs<Compatibility.Unknown>(unknown)
        assertTrue(AssessmentReason.ENGINE_SUPPORT_UNKNOWN in unknown.reasons)
    }

    @Test
    fun supportedEngineEvidenceProducesCompatibility() {
        val checker = CompatibilityChecker(EngineCapabilitySource { SupportEvidence.Supported })
        assertEquals(Compatibility.Compatible, checker.check(llmDescriptor(), hardwareProfile()))
    }

    @Test
    fun missingGgufVersionRemainsUnknownEvenWhenOtherEngineFeaturesAreSupported() {
        val checker = CompatibilityChecker(EngineCapabilitySource { SupportEvidence.Supported })
        val result = checker.check(llmDescriptor(ggufVersion = null), hardwareProfile())
        assertIs<Compatibility.Unknown>(result)
        assertTrue(AssessmentReason.GGUF_VERSION_UNKNOWN in result.reasons)
    }

    private fun llmDescriptor(ggufVersion: Int? = 3) = LlmModelDescriptor(
        repositoryId = "owner/model",
        revision = REVISION,
        file = identity("model-Q4_K_M.gguf"),
        architecture = "llama",
        quantization = QuantizationEvidence.Known("Q4_K_M"),
        parameterCount = 7_000_000_000L,
        contextLimit = 8_192,
        transformerShape = null,
        ggufVersion = ggufVersion,
        requiredEngineFeatures = emptySet(),
        evidence = emptyList(),
    )

    private fun diffusionDescriptor(requiredComponentsPresent: Boolean) = DiffusionModelDescriptor(
        repositoryId = "owner/model",
        revision = REVISION,
        components = listOf(
            DiffusionComponentDescriptor(
                file = identity("main.safetensors"),
                role = null,
                required = true,
                isPrimary = true,
            ),
        ),
        mode = DiffusionMode.IMAGE,
        family = "family",
        quantizationDistribution = emptySet(),
        requiredComponentsPresent = requiredComponentsPresent,
        requiredEngineFeatures = emptySet(),
        evidence = emptyList(),
    )

    private fun identity(path: String) = ModelFileIdentity(
        repositoryId = "owner/model",
        revision = REVISION,
        path = path,
        sizeBytes = 1L,
        gitOid = null,
        lfsOid = null,
        xetHash = null,
        evidence = emptyList(),
    )

    private fun hardwareProfile() = HardwareProfile(
        cpuArchitecture = "test",
        logicalCoreCount = 8,
        performanceCoreCount = 4,
        instructionSets = emptySet(),
        backends = listOf(
            BackendCapability(
                kind = BackendKind.CPU,
                status = BackendStatus.AVAILABLE,
                additionalAllocatableBytes = null,
                availabilityConfidence = Confidence.HIGH,
                headroomConfidence = null,
                evidence = emptyList(),
            ),
        ),
        memoryTopology = MemoryTopology.UNIFIED,
        evidence = emptyList(),
    )

    private companion object {
        const val REVISION = "0123456789abcdef0123456789abcdef01234567"
    }
}
