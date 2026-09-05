package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.MemoryTopology
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

    private fun engine(support: SupportEvidence): SuitabilityEngine = SuitabilityEngine(
        compatibilityChecker = CompatibilityChecker(EngineCapabilitySource { support }),
        calibrationSource = NoCalibrationSource,
    )
}
