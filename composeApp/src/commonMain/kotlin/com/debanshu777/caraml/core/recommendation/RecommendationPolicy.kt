package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.platform.DeviceSnapshot

class RecommendationPolicy(
    private val runPlanOptimizer: RunPlanOptimizer = RunPlanOptimizer(),
) {
    fun recommend(
        assessment: ModelAssessment,
        snapshot: DeviceSnapshot,
        profile: RecommendationProfile,
    ): PersonalizedRecommendation {
        when (val compatibility = assessment.compatibility) {
            is Compatibility.Incompatible -> return PersonalizedRecommendation(
                assessmentKey = assessment.assessmentKey,
                category = RecommendationCategory.INCOMPATIBLE,
                selectedPlan = null,
                reasons = compatibility.reasons.distinct().ifEmpty {
                    listOf(AssessmentReason.UNSUPPORTED_ENGINE_FEATURE)
                },
                profile = profile,
            )
            is Compatibility.Unknown -> return PersonalizedRecommendation(
                assessmentKey = assessment.assessmentKey,
                category = RecommendationCategory.NEEDS_INFORMATION,
                selectedPlan = null,
                reasons = compatibility.reasons.distinct().ifEmpty {
                    listOf(AssessmentReason.ENGINE_SUPPORT_UNKNOWN)
                },
                profile = profile,
            )
            Compatibility.Compatible -> Unit
        }
        if (!runPlanOptimizer.snapshotIsFresh(snapshot)) {
            return PersonalizedRecommendation(
                assessmentKey = assessment.assessmentKey,
                category = RecommendationCategory.NEEDS_INFORMATION,
                selectedPlan = null,
                reasons = listOf(AssessmentReason.RESOURCE_SNAPSHOT_STALE),
                profile = profile,
            )
        }
        val selected = runPlanOptimizer.select(assessment, snapshot, profile)
        return PersonalizedRecommendation(
            assessmentKey = assessment.assessmentKey,
            category = selected.category,
            selectedPlan = selected.plan,
            reasons = selected.reasons.ifEmpty { listOf(AssessmentReason.MEMORY_BOUNDS_UNKNOWN) },
            profile = profile,
        )
    }

    fun sortKey(
        assessment: ModelAssessment,
        snapshot: DeviceSnapshot,
        profile: RecommendationProfile,
    ): RecommendationSortKey {
        val recommendation = recommend(assessment, snapshot, profile)
        val selected = if (
            recommendation.category == RecommendationCategory.INCOMPATIBLE ||
            recommendation.category == RecommendationCategory.NEEDS_INFORMATION && assessment.planAssessments.values.isEmpty()
        ) null else runPlanOptimizer.select(assessment, snapshot, profile)
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
}
