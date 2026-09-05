package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.MemoryTopology
import com.debanshu777.caraml.core.rating.SdArchitecture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RunPlanOptimizerTest {
    private val optimizer = RunPlanOptimizer()

    @Test
    fun versionedUtilityAndQualityConstantsMatchThePolicyFixture() {
        assertEquals(0.05, RecommendationPolicyV1.UTILITY_METRIC_MIN)
        assertEquals(1.0, RecommendationPolicyV1.UTILITY_METRIC_MAX)
        assertEquals(
            mapOf(
                OptimizationPriority.SPEED_EFFICIENCY to
                    RecommendationPolicyV1.UtilityWeights(0.60, 0.25, 0.05, 0.00, 0.10),
                OptimizationPriority.BALANCED to
                    RecommendationPolicyV1.UtilityWeights(0.30, 0.10, 0.30, 0.20, 0.10),
                OptimizationPriority.QUALITY_CONTEXT to
                    RecommendationPolicyV1.UtilityWeights(0.10, 0.05, 0.55, 0.25, 0.05),
            ),
            RecommendationPolicyV1.utilityWeights,
        )
        assertEquals(
            RecommendationPolicyV1.QualityProxyWeights(0.50, 0.35),
            RecommendationPolicyV1.llmQualityProxyWeights,
        )
        assertEquals(
            RecommendationPolicyV1.QualityProxyWeights(0.35, 0.65),
            RecommendationPolicyV1.diffusionQualityProxyWeights,
        )
        assertEquals(0.92, RecommendationPolicyV1.llmQuantizationQualityProxy.getValue("Q8_0"))
        assertEquals(0.80, RecommendationPolicyV1.diffusionArchitectureQualityProxy.getValue(SdArchitecture.SDXL))
        assertEquals(13_000_000_000L, RecommendationPolicyV1.LLM_PARAMETER_QUALITY_TARGET)
        assertEquals(1_073_741_824L, RecommendationPolicyV1.STORAGE_EFFICIENCY_TARGET_BYTES)
    }

    @Test
    fun directSelectionCannotBypassHardIncompatibility() {
        val selected = optimizer.select(
            task6Assessment(
                compatibility = Compatibility.Incompatible(listOf(AssessmentReason.UNSUPPORTED_FORMAT)),
            ),
            task6Snapshot(),
            RecommendationProfile(riskTolerance = RiskTolerance.EXPERIMENTAL),
        )

        assertEquals(RecommendationCategory.INCOMPATIBLE, selected.category)
        assertEquals(null, selected.plan)
    }

    @Test
    fun safetyCategoryPrecedesHigherPreferenceUtility() {
        val safe = task6PlanAssessment(
            plan = task6LlmPlan(keyContext = 4_096),
            host = task6Range(400, 500, 600),
            metrics = PlanUtilityMetrics(performance = 0.1, energy = 0.1, quality = 0.1, context = 0.1, storageEfficiency = 0.1),
        )
        val fastButTight = task6PlanAssessment(
            plan = task6LlmPlan(keyContext = 2_048),
            host = task6Range(700, 800, 900),
            metrics = PlanUtilityMetrics(performance = 1.0, energy = 1.0, quality = 1.0, context = 1.0, storageEfficiency = 1.0),
        )

        val selected = optimizer.select(
            task6Assessment(plans = listOf(safe, fastButTight)),
            task6Snapshot(hostBudget = 1_000, storageBudget = 2_000),
            RecommendationProfile(),
        )

        assertEquals(safe.plan.stableKey, selected.plan?.stableKey)
        assertEquals(RecommendationCategory.RECOMMENDED, selected.category)
    }

    @Test
    fun unusedLowConfidenceCandidateDoesNotCapTheSelectedHighConfidencePlan() {
        val selectedPlan = task6PlanAssessment(
            plan = task6LlmPlan(keyContext = 4_096),
            host = task6Range(100, 100, 100),
            confidence = task6Confidence(memory = Confidence.HIGH),
            metrics = PlanUtilityMetrics(quality = 1.0),
        )
        val unusedLowCandidate = task6PlanAssessment(
            plan = task6LlmPlan(keyContext = 2_048),
            host = task6Range(900, 900, 900),
            confidence = task6Confidence(memory = Confidence.LOW),
            metrics = PlanUtilityMetrics(quality = 0.1),
        )

        val selected = optimizer.select(
            task6Assessment(
                plans = listOf(selectedPlan, unusedLowCandidate),
                confidence = task6Confidence(memory = Confidence.LOW),
            ),
            task6Snapshot(hostConfidence = Confidence.HIGH),
            RecommendationProfile(),
        )

        assertEquals(selectedPlan.plan.stableKey, selected.plan?.stableKey)
        assertEquals(RecommendationCategory.RECOMMENDED, selected.category)
        assertEquals(Confidence.HIGH, selected.safetyConfidence)
    }

    @Test
    fun equalCandidatesPreserveOriginalCandidateOrderAsFinalTieBreaker() {
        val first = task6PlanAssessment(plan = task6LlmPlan(keyContext = 4_096))
        val second = task6PlanAssessment(plan = task6LlmPlan(keyContext = 2_048))

        val selected = optimizer.select(
            task6Assessment(plans = listOf(first, second)),
            task6Snapshot(),
            RecommendationProfile(),
        )

        assertEquals(first.plan.stableKey, selected.plan?.stableKey)
        assertEquals(0, selected.candidateIndex)
    }

    @Test
    fun discreteHostAndGpuPoolsAreComparedIndependentlyAndNeverAdded() {
        val plan = task6PlanAssessment(
            plan = task6LlmPlan(
                backend = BackendKind.CUDA,
                topology = MemoryTopology.DISCRETE,
                gpuLayers = 16,
            ),
            host = task6Range(400, 400, 400),
            gpu = task6Range(400, 400, 400),
        )

        val selected = optimizer.select(
            task6Assessment(plans = listOf(plan)),
            task6Snapshot(hostBudget = 500, gpuBudget = 500, storageBudget = 2_000),
            RecommendationProfile(),
        )

        assertEquals(FitBand.COMFORTABLE, selected.fitBand)
        assertEquals(RecommendationCategory.RECOMMENDED, selected.category)
    }

    @Test
    fun remainingHeadroomUsesWorstNormalizedRequiredPool() {
        val gpuBound = task6PlanAssessment(
            plan = task6LlmPlan(
                keyContext = 4_096,
                backend = BackendKind.CUDA,
                topology = MemoryTopology.DISCRETE,
                gpuLayers = 16,
            ),
            host = task6Range(100, 100, 100),
            gpu = task6Range(800, 800, 800),
        )
        val balancedPools = task6PlanAssessment(
            plan = task6LlmPlan(
                keyContext = 2_048,
                backend = BackendKind.CUDA,
                topology = MemoryTopology.DISCRETE,
                gpuLayers = 16,
            ),
            host = task6Range(500, 500, 500),
            gpu = task6Range(500, 500, 500),
        )

        val selected = optimizer.select(
            task6Assessment(plans = listOf(gpuBound, balancedPools)),
            task6Snapshot(hostBudget = 1_000, gpuBudget = 1_000, storageBudget = 2_000),
            RecommendationProfile(),
        )

        assertEquals(balancedPools.plan.stableKey, selected.plan?.stableKey)
        assertTrue(selected.worstNormalizedHeadroom > 0.4)
    }

    @Test
    fun weightedGeometricUtilityUsesExactVectorsAndRenormalizesMissingMetrics() {
        val complete = PlanUtilityMetrics(
            performance = 0.25,
            energy = 0.5,
            quality = 1.0,
            context = 0.1,
            storageEfficiency = 0.5,
        )
        val missingEnergy = complete.copy(energy = null)

        assertEquals(0.341510, preferenceUtility(complete, OptimizationPriority.SPEED_EFFICIENCY), 0.000_001)
        assertEquals(0.349658, preferenceUtility(missingEnergy, OptimizationPriority.BALANCED), 0.000_001)
        assertEquals(
            preferenceUtility(complete.copy(performance = 0.05), OptimizationPriority.SPEED_EFFICIENCY),
            preferenceUtility(complete.copy(performance = -500.0), OptimizationPriority.SPEED_EFFICIENCY),
            0.000_001,
        )
    }

    @Test
    fun performanceUtilityUsesTheSelectedProfilesVersionedTarget() {
        val assessedPlan = task6PlanAssessment(
            performance = task6LlmPerformance(decodeLikely = 3.0, confidence = Confidence.HIGH),
            confidence = task6Confidence(performance = Confidence.HIGH),
            metrics = PlanUtilityMetrics(),
        )
        val assessment = task6Assessment(plans = listOf(assessedPlan))
        val snapshot = task6Snapshot()

        val speed = optimizer.select(
            assessment,
            snapshot,
            RecommendationProfile(optimizationPriority = OptimizationPriority.SPEED_EFFICIENCY),
        )
        val quality = optimizer.select(
            assessment,
            snapshot,
            RecommendationProfile(optimizationPriority = OptimizationPriority.QUALITY_CONTEXT),
        )

        assertEquals(3.0 / 8.0, speed.utility, 0.000_001)
        assertEquals(1.0, quality.utility, 0.000_001)
    }

    @Test
    fun recommendationSortKeyOrdersSafetyConfidenceBeforeSpeedAndUtility() {
        val safer = RecommendationSortKey.create(
            category = RecommendationCategory.USABLE,
            confidence = AssessmentConfidence(Confidence.HIGH, Confidence.HIGH, Confidence.HIGH, Confidence.LOW),
            utility = 0.1,
            worstNormalizedHeadroom = 0.1,
            stableId = "z/model",
        )
        val faster = RecommendationSortKey.create(
            category = RecommendationCategory.USABLE,
            confidence = AssessmentConfidence(Confidence.MEDIUM, Confidence.MEDIUM, Confidence.MEDIUM, Confidence.HIGH),
            utility = 1.0,
            worstNormalizedHeadroom = 1.0,
            stableId = "a/model",
        )

        assertTrue(safer < faster)
        assertTrue(
            RecommendationSortKey.create(
                RecommendationCategory.USABLE,
                AssessmentConfidence(Confidence.HIGH, Confidence.HIGH, Confidence.HIGH, Confidence.HIGH),
                0.5,
                0.5,
                "a/model",
            ) < RecommendationSortKey.create(
                RecommendationCategory.USABLE,
                AssessmentConfidence(Confidence.HIGH, Confidence.HIGH, Confidence.HIGH, Confidence.HIGH),
                0.5,
                0.5,
                "b/model",
            ),
        )
    }
}
