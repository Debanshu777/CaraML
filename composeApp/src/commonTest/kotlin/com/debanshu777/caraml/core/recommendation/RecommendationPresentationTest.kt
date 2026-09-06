package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.MemoryTopology
import com.debanshu777.caraml.core.rating.ui.recommendationCategoryLabel
import com.debanshu777.caraml.core.rating.ui.recommendationPrimaryReasonLabel
import com.debanshu777.caraml.core.rating.ui.recommendationSemantics
import com.debanshu777.caraml.core.rating.ui.recommendationPresentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecommendationPresentationTest {
    @Test
    fun presentationNamesCategoryConfidenceAndOrderedPrimaryReason() {
        val recommendation = recommendation(
            reasons = listOf(AssessmentReason.TIGHT_MEMORY_FIT, AssessmentReason.SPEED_NOT_VERIFIED),
        )

        assertEquals("Recommended", recommendationCategoryLabel(recommendation.category))
        assertEquals("Tight memory fit", recommendationPrimaryReasonLabel(recommendation.reasons.first()))
        assertEquals(
            "Recommended. Medium confidence. Tight memory fit.",
            recommendationSemantics(recommendation),
        )
    }

    @Test
    fun llmPlanPresentationDisclosesSelectedWorkloadAndCompromises() {
        val plan = LlmRunPlan(
            contextTokens = 2_048,
            batchSize = 128,
            microBatchSize = 32,
            sequenceCount = 1,
            keyCacheType = KvCacheType.Q8_0,
            valueCacheType = KvCacheType.Q8_0,
            backend = BackendKind.CPU,
            memoryTopology = MemoryTopology.DISCRETE,
            gpuLayerCount = null,
            compromises = listOf(RunPlanCompromise.CONTEXT_REDUCED),
        )
        val presentation = recommendationPresentation(
            recommendation = recommendation(selectedPlan = plan),
            selectedVariant = "model-q4.gguf",
            workload = llmWorkload(),
        )

        assertEquals("model-q4.gguf", presentation.selectedVariant)
        assertTrue(presentation.assumedWorkload.contains("4,096"))
        assertTrue(presentation.selectedPlan.contains("2,048"))
        assertTrue(presentation.selectedPlan.contains("context reduced", ignoreCase = true))
        assertEquals("Medium", presentation.confidence)
    }

    @Test
    fun missingConfidenceRemainsVisiblyUnavailable() {
        val recommendation = recommendation(confidence = null)

        assertEquals("Unavailable", recommendationPresentation(recommendation, null, null).confidence)
        assertTrue(recommendationSemantics(recommendation).contains("Unavailable confidence"))
    }
}

internal fun recommendation(
    selectedPlan: PlanReference? = null,
    fallbackPlan: PlanReference? = null,
    reasons: List<AssessmentReason> = listOf(AssessmentReason.MEMORY_FIT_COMFORTABLE),
    profile: RecommendationProfile = RecommendationProfile(
        RiskTolerance.EXPERIMENTAL,
        OptimizationPriority.BALANCED,
    ),
    confidence: AssessmentConfidence? = AssessmentConfidence(
        Confidence.HIGH,
        Confidence.MEDIUM,
        Confidence.HIGH,
        Confidence.LOW,
    ),
) = PersonalizedRecommendation(
    assessmentKey = "assessment",
    category = RecommendationCategory.RECOMMENDED,
    selectedPlan = selectedPlan,
    reasons = reasons,
    profile = profile,
    confidence = confidence,
    memoryFit = FitBand.LIKELY,
    storageFit = FitBand.COMFORTABLE,
    fallbackPlan = fallbackPlan,
)

internal fun llmWorkload() = LlmWorkloadConfig(
    userRequestedContextTokens = 4_096,
    contextTokens = 4_096,
    minimumContextTokens = 512,
    promptTokens = 256,
    generationReserveTokens = 256,
    batchSize = 128,
    microBatchSize = 32,
    sequenceCount = 1,
    kvCacheSelection = KvCacheSelection.Auto,
    allowContextFallback = true,
    allowBatchFallback = true,
    allowKvCacheFallback = true,
    allowedKvCacheTypes = listOf(KvCacheType.F16, KvCacheType.Q8_0),
    evidence = emptyList(),
)
