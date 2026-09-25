package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.platform.DeviceSnapshot

class RecommendationPolicy(
    private val runPlanOptimizer: RunPlanOptimizer = RunPlanOptimizer(),
) {
    fun recommend(
        assessment: ModelAssessment,
        snapshot: DeviceSnapshot,
        profile: RecommendationProfile,
    ): PersonalizedRecommendation = evaluate(assessment, snapshot, profile).recommendation

    private fun evaluate(
        assessment: ModelAssessment,
        snapshot: DeviceSnapshot,
        profile: RecommendationProfile,
    ): PolicyEvaluation {
        val selection = runPlanOptimizer.select(assessment, snapshot, profile)
        val recommendation = PersonalizedRecommendation(
            assessmentKey = assessment.assessmentKey,
            category = selection.category,
            selectedPlan = selection.plan,
            reasons = selection.reasons.ifEmpty { listOf(AssessmentReason.MEMORY_BOUNDS_UNKNOWN) },
            profile = profile,
            confidence = AssessmentConfidence(
                compatibility = assessment.confidence.compatibility,
                memory = selection.safetyConfidence,
                storage = selection.safetyConfidence,
                performance = selection.performanceConfidence,
            ),
            memoryFit = selection.fitBand,
            storageFit = selection.storageFitBand,
            selectedPlanAssessment = selection.planAssessment,
            fallbackPlan = selection.fallbackPlanAssessment?.plan,
        )
        return PolicyEvaluation(recommendation, selection)
    }

    fun sortKey(
        assessment: ModelAssessment,
        snapshot: DeviceSnapshot,
        profile: RecommendationProfile,
    ): RecommendationSortKey {
        val evaluation = evaluate(assessment, snapshot, profile)
        val recommendation = evaluation.recommendation
        val selected = if (
            recommendation.category == RecommendationCategory.INCOMPATIBLE ||
            recommendation.category == RecommendationCategory.NEEDS_INFORMATION && assessment.planAssessments.values.isEmpty()
        ) null else evaluation.selection
        val confidence = selected?.let {
            AssessmentConfidence(
                compatibility = assessment.confidence.compatibility,
                memory = it.safetyConfidence,
                storage = it.safetyConfidence,
                performance = it.performanceConfidence,
            )
        } ?: assessment.confidence
        return RecommendationSortKey.create(
            category = recommendation.category,
            confidence = confidence,
            utility = selected?.utility ?: 0.0,
            worstNormalizedHeadroom = selected?.worstNormalizedHeadroom ?: -1.0,
            stableId = buildString {
                append(assessment.assessmentKey)
                selected?.plan?.stableKey?.let { append(':').append(it) }
            },
        )
    }

    private data class PolicyEvaluation(
        val recommendation: PersonalizedRecommendation,
        val selection: SelectedPlan,
    )
}
