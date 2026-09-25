package com.debanshu777.caraml.core.recommendation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class AssessmentModelsTest {
    // Exact constructor types make adding policy fields to ModelAssessment (even with defaults)
    // a compile-time contract failure without relying on JVM reflection.
    private val objectiveAssessmentConstructor: (
        String,
        Compatibility,
        AssessedPlans,
        Long?,
        Long?,
        Long?,
        Long?,
        AssessmentConfidence,
        Collection<Evidence>,
    ) -> ModelAssessment = ::ModelAssessment

    private val personalizedRecommendationConstructor: (
        String,
        RecommendationCategory,
        PlanReference?,
        Collection<AssessmentReason>,
        RecommendationProfile,
    ) -> PersonalizedRecommendation = ::PersonalizedRecommendation

    private data class FixturePlan(
        override val stableKey: String,
    ) : PlanReference

    @Test
    fun objectiveAssessmentRemainsIndependentOfRecommendationPolicy() {
        val plan = FixturePlan(stableKey = "repo/model@revision:plan-1")
        val confidence = AssessmentConfidence(
            compatibility = Confidence.HIGH,
            memory = Confidence.MEDIUM,
            storage = Confidence.HIGH,
            performance = Confidence.LOW,
        )
        val evidence = listOf(
            Evidence(
                reason = AssessmentReason.INVALID_METADATA,
                confidence = Confidence.LOW,
                detail = "Optional performance calibration was unavailable",
            ),
        )
        val planAssessment = PlanAssessment(
            plan = plan,
            hostMemoryBytes = validRange(1_000L, 1_200L, 1_500L),
            gpuMemoryBytes = null,
            sharedMemoryBytes = null,
            storageBytes = validRange(2_000L, 2_000L, 2_000L),
            confidence = confidence,
            evidence = evidence,
        )
        val assessment = objectiveAssessmentConstructor(
            "assessment-key",
            Compatibility.Compatible,
            AssessedPlans(listOf(planAssessment)),
            4_000L,
            null,
            null,
            8_000L,
            confidence,
            evidence,
        )

        assertEquals("assessment-key", assessment.assessmentKey)
        assertSame(Compatibility.Compatible, assessment.compatibility)
        assertEquals(listOf(planAssessment), assessment.planAssessments.values)
        assertEquals(4_000L, assessment.baseHostBudgetBytes)
        assertNull(assessment.baseGpuBudgetBytes)
        assertNull(assessment.baseSharedBudgetBytes)
        assertEquals(8_000L, assessment.baseStorageBudgetBytes)
        assertEquals(confidence, assessment.confidence)
        assertEquals(evidence, assessment.evidence)
    }

    @Test
    fun personalizedRecommendationOwnsCategorySelectionReasonsAndProfile() {
        val selectedPlan = FixturePlan(stableKey = "repo/model@revision:plan-1")
        val profile = RecommendationProfile(
            riskTolerance = RiskTolerance.BALANCED,
            optimizationPriority = OptimizationPriority.QUALITY_CONTEXT,
        )
        val recommendation = personalizedRecommendationConstructor(
            "assessment-key",
            RecommendationCategory.USABLE,
            selectedPlan,
            listOf(AssessmentReason.INVALID_METADATA),
            profile,
        )

        assertEquals("assessment-key", recommendation.assessmentKey)
        assertEquals(RecommendationCategory.USABLE, recommendation.category)
        assertEquals(selectedPlan, recommendation.selectedPlan)
        assertEquals(listOf(AssessmentReason.INVALID_METADATA), recommendation.reasons)
        assertEquals(profile, recommendation.profile)
    }

    @Test
    fun contractsSnapshotCallerOwnedCollections() {
        val reasons = mutableListOf(AssessmentReason.INVALID_METADATA)
        val evidence = mutableListOf(
            Evidence(
                reason = AssessmentReason.INVALID_METADATA,
                confidence = Confidence.LOW,
            ),
        )
        val plan = FixturePlan(stableKey = "repo/model@revision:plan-1")
        val confidence = AssessmentConfidence(
            compatibility = Confidence.LOW,
            memory = Confidence.LOW,
            storage = Confidence.LOW,
            performance = Confidence.LOW,
        )
        val incompatible = Compatibility.Incompatible(reasons, evidence)
        val unknown = Compatibility.Unknown(reasons, evidence)
        val planAssessment = PlanAssessment(
            plan = plan,
            hostMemoryBytes = null,
            gpuMemoryBytes = null,
            sharedMemoryBytes = null,
            storageBytes = null,
            confidence = confidence,
            evidence = evidence,
        )
        val callerPlans = mutableListOf(planAssessment)
        val assessedPlans = AssessedPlans(callerPlans)
        val assessment = ModelAssessment(
            assessmentKey = "assessment-key",
            compatibility = incompatible,
            planAssessments = assessedPlans,
            baseHostBudgetBytes = null,
            baseGpuBudgetBytes = null,
            baseSharedBudgetBytes = null,
            baseStorageBudgetBytes = null,
            confidence = confidence,
            evidence = evidence,
        )
        val recommendation = PersonalizedRecommendation(
            assessmentKey = assessment.assessmentKey,
            category = RecommendationCategory.NEEDS_INFORMATION,
            selectedPlan = null,
            reasons = reasons,
            profile = RecommendationProfile(),
        )

        reasons.clear()
        evidence.clear()
        callerPlans.clear()

        assertEquals(listOf(AssessmentReason.INVALID_METADATA), incompatible.reasons)
        assertEquals(listOf(AssessmentReason.INVALID_METADATA), unknown.reasons)
        assertEquals(1, incompatible.evidence.size)
        assertEquals(1, unknown.evidence.size)
        assertEquals(1, planAssessment.evidence.size)
        assertEquals(listOf(planAssessment), assessedPlans.values)
        assertEquals(1, assessment.evidence.size)
        assertEquals(listOf(AssessmentReason.INVALID_METADATA), recommendation.reasons)
    }

    private fun validRange(low: Long, likely: Long, high: Long): EstimateRange =
        when (val checked = EstimateRange.create(low, likely, high)) {
            is CheckedEstimateRange.Value -> checked.range
            is CheckedEstimateRange.Invalid -> error("Fixture range was invalid: ${checked.reason}")
        }
}
