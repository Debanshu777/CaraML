package com.debanshu777.caraml.core.recommendation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class AssessmentModelsTest {
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
        val assessment = ModelAssessment(
            assessmentKey = "assessment-key",
            compatibility = Compatibility.Compatible,
            planAssessments = AssessedPlans(listOf(planAssessment)),
            baseHostBudgetBytes = 4_000L,
            baseGpuBudgetBytes = null,
            baseSharedBudgetBytes = null,
            baseStorageBudgetBytes = 8_000L,
            confidence = confidence,
            evidence = evidence,
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
        val recommendation = PersonalizedRecommendation(
            assessmentKey = "assessment-key",
            category = RecommendationCategory.USABLE,
            selectedPlan = selectedPlan,
            reasons = listOf(AssessmentReason.INVALID_METADATA),
            profile = profile,
        )

        assertEquals("assessment-key", recommendation.assessmentKey)
        assertEquals(RecommendationCategory.USABLE, recommendation.category)
        assertEquals(selectedPlan, recommendation.selectedPlan)
        assertEquals(listOf(AssessmentReason.INVALID_METADATA), recommendation.reasons)
        assertEquals(profile, recommendation.profile)
    }

    private fun validRange(low: Long, likely: Long, high: Long): EstimateRange =
        when (val checked = EstimateRange.create(low, likely, high)) {
            is CheckedEstimateRange.Value -> checked.range
            is CheckedEstimateRange.Invalid -> error("Fixture range was invalid: ${checked.reason}")
        }
}
