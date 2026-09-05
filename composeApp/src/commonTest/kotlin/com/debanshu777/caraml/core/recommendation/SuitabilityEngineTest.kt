package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.MemoryTopology
import com.debanshu777.caraml.core.rating.SdArchitecture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SuitabilityEngineTest {
    @Test
    fun hardCompatibilityRunsBeforeCandidateGenerationAndEstimation() {
        val engine = engine(
            SupportEvidence.Unsupported(
                reasons = listOf(AssessmentReason.UNSUPPORTED_ARCHITECTURE),
            ),
        )

        val assessed = engine.assessPlans(
            descriptor = task6LlmDescriptor(),
            hardwareProfile = task6Hardware(),
            workload = task6InvalidLlmWorkload(),
        )

        assertIs<Compatibility.Incompatible>(assessed.compatibility)
        assertTrue(assessed.values.isEmpty())
        assertEquals(listOf(AssessmentReason.UNSUPPORTED_ARCHITECTURE), assessed.reasons)
    }

    @Test
    fun unknownCompatibilityDoesNotCreateSpeculativePlans() {
        val assessed = engine(SupportEvidence.Unknown()).assessPlans(
            task6LlmDescriptor(),
            task6Hardware(),
            task6LlmWorkload(),
        )

        assertIs<Compatibility.Unknown>(assessed.compatibility)
        assertTrue(assessed.values.isEmpty())
    }

    @Test
    fun supportedDescriptorEstimatesEveryBoundedCandidateWithoutAProfile() {
        val assessed = engine(SupportEvidence.Supported).assessPlans(
            task6LlmDescriptor(),
            task6Hardware(),
            task6LlmWorkload(allowFallbacks = true),
        )

        assertEquals(Compatibility.Compatible, assessed.compatibility)
        assertTrue(assessed.values.size in 2..24)
        assertTrue(assessed.values.all { it.plan is LlmRunPlan })
        assertTrue(assessed.values.all { it.performance is PerformanceEstimate.Unknown })
        assertTrue(assessed.values.map { it.plan.stableKey }.distinct().size == assessed.values.size)
    }

    @Test
    fun assemblyUsesCurrentResourceFactsAndNeverStoresAUserProfile() {
        val engine = engine(SupportEvidence.Supported)
        val assessed = engine.assessPlans(task6LlmDescriptor(), task6Hardware(), task6LlmWorkload())
        val roomy = engine.assemble(
            assessed,
            task6ResourceSnapshot(hostBytes = 4_000L, storageBytes = 2_147_483_648L),
        )
        val tight = engine.assemble(
            assessed,
            task6ResourceSnapshot(hostBytes = 2_000L, storageBytes = 1_073_741_824L),
        )

        assertEquals(assessed.assessmentKey, roomy.assessmentKey)
        assertEquals(assessed.values, roomy.planAssessments.values)
        assertNotEquals(roomy.baseHostBudgetBytes, tight.baseHostBudgetBytes)
        assertNotEquals(roomy.baseStorageBudgetBytes, tight.baseStorageBudgetBytes)
        // Compile-time ownership regression: neither assessPlans nor assemble accepts a RecommendationProfile.
        assertEquals(Compatibility.Compatible, roomy.compatibility)
    }

    @Test
    fun invalidCandidateInputsBecomeStructuredEvidenceInsteadOfThrowing() {
        val assessed = engine(SupportEvidence.Supported).assessPlans(
            task6LlmDescriptor(),
            task6Hardware(),
            task6InvalidLlmWorkload(),
        )

        assertTrue(assessed.values.isEmpty())
        assertTrue(assessed.reasons.contains(AssessmentReason.INVALID_WORKLOAD))
    }

    @Test
    fun validatedLlmQuantizationAndParameterScaleAffectEvidencedQualityUtility() {
        val engine = engine(SupportEvidence.Supported)
        val lower = engine.assessPlans(
            task6LlmDescriptor(
                parameterCount = 3_000_000_000L,
                quantization = QuantizationEvidence.Known("Q4_K_M"),
            ),
            task6Hardware(),
            task6LlmWorkload(),
        ).values.first()
        val higher = engine.assessPlans(
            task6LlmDescriptor(
                parameterCount = 13_000_000_000L,
                quantization = QuantizationEvidence.Known("Q8_0"),
            ),
            task6Hardware(),
            task6LlmWorkload(),
        ).values.first()

        assertTrue(requireNotNull(higher.utilityMetrics.quality) > requireNotNull(lower.utilityMetrics.quality))
        assertEquals(0.9521354, higher.utilityMetrics.quality, 0.0000001)
        assertEquals(1.0, higher.utilityMetrics.storageEfficiency)
        assertTrue(higher.evidence.any {
            it.reason == AssessmentReason.QUALITY_PROXY_USED &&
                it.detail?.contains("not-benchmark") == true
        })
    }

    @Test
    fun unrecognizedQuantizationTokenIsOmittedWithoutLooseStringGuessing() {
        val engine = engine(SupportEvidence.Supported)
        fun quality(quantization: QuantizationEvidence): Double? = engine.assessPlans(
            task6LlmDescriptor(parameterCount = null, quantization = quantization),
            task6Hardware(),
            task6LlmWorkload(),
        ).values.first().utilityMetrics.quality

        val unknown = quality(QuantizationEvidence.Unknown)
        assertEquals(null, unknown)
        assertEquals(unknown, quality(QuantizationEvidence.Known("Q4ISH")))
    }

    @Test
    fun diffusionQualityUsesTypedArchitectureAndExactQuantizationOrIsOmitted() {
        val engine = engine(SupportEvidence.Supported)
        val hardware = task6Hardware(
            backend = BackendKind.METAL,
            topology = MemoryTopology.UNIFIED,
        )
        val known = engine.assessPlans(
            task6DiffusionDescriptor(
                architecture = SdArchitecture.SDXL,
                quantizationDistribution = setOf("F16"),
            ),
            hardware,
            task6DiffusionWorkload(),
        ).values.first()
        val unknown = engine.assessPlans(
            task6DiffusionDescriptor(
                architecture = SdArchitecture.UNKNOWN,
                quantizationDistribution = emptySet(),
            ),
            hardware,
            task6DiffusionWorkload(),
        ).values.first()

        assertTrue(requireNotNull(known.utilityMetrics.quality) > 0.5)
        assertEquals(null, unknown.utilityMetrics.quality)
        assertTrue(unknown.evidence.any { it.reason == AssessmentReason.QUALITY_NOT_VERIFIED })
    }

    @Test
    fun cpuLlmOnUnifiedMemoryUsesTheSharedDevicePoolEndToEnd() {
        val result = recommendCpu(
            descriptor = task6LlmDescriptor(),
            workload = task6LlmWorkload(),
            topology = MemoryTopology.UNIFIED,
        )

        assertEquals(RecommendationCategory.RECOMMENDED, result.category, result.toString())
        assertTrue(result.selectedPlan is LlmRunPlan)
    }

    @Test
    fun cpuDiffusionOnUnifiedMemoryUsesTheSharedDevicePoolEndToEnd() {
        val result = recommendCpu(
            descriptor = task6DiffusionDescriptor(),
            workload = task6DiffusionWorkload(),
            topology = MemoryTopology.UNIFIED,
        )

        assertEquals(RecommendationCategory.RECOMMENDED, result.category, result.toString())
        assertTrue(result.selectedPlan is DiffusionRunPlan)
    }

    @Test
    fun cpuOnDiscreteMemoryContinuesToUseOnlyTheHostPool() {
        val result = recommendCpu(
            descriptor = task6LlmDescriptor(),
            workload = task6LlmWorkload(),
            topology = MemoryTopology.DISCRETE,
        )

        assertEquals(RecommendationCategory.RECOMMENDED, result.category, result.toString())
        assertTrue(result.selectedPlan is LlmRunPlan)
    }

    private fun recommendCpu(
        descriptor: ModelDescriptor,
        workload: WorkloadConfig,
        topology: MemoryTopology,
    ): PersonalizedRecommendation {
        val hardware = task6Hardware(backend = BackendKind.CPU, topology = topology)
        val suitability = engine(SupportEvidence.Supported)
        val assessed = suitability.assessPlans(descriptor, hardware, workload)
        val snapshot = if (topology == MemoryTopology.UNIFIED) {
            task6Snapshot(
                hostBudget = null,
                sharedBudget = 20_000_000_000L,
                storageBudget = 20_000_000_000L,
                topology = topology,
                backends = listOf(task6Backend(BackendKind.CPU)),
            )
        } else {
            task6Snapshot(
                hostBudget = 20_000_000_000L,
                storageBudget = 20_000_000_000L,
                topology = topology,
                backends = listOf(task6Backend(BackendKind.CPU)),
            )
        }
        return RecommendationPolicy().recommend(
            suitability.assemble(assessed, snapshot),
            snapshot,
            RecommendationProfile(),
        )
    }

    private fun engine(support: SupportEvidence): SuitabilityEngine = SuitabilityEngine(
        compatibilityChecker = CompatibilityChecker(EngineCapabilitySource { support }),
        calibrationSource = NoCalibrationSource,
    )
}
