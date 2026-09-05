package com.debanshu777.caraml.core.recommendation

enum class Confidence {
    LOW,
    MEDIUM,
    HIGH,
}

data class AssessmentConfidence(
    val compatibility: Confidence,
    val memory: Confidence,
    val storage: Confidence,
    val performance: Confidence,
)

enum class AssessmentReason {
    INVALID_METADATA,
    ARITHMETIC_OVERFLOW,
    INVALID_ESTIMATE_RANGE,
}

data class Evidence(
    val reason: AssessmentReason,
    val confidence: Confidence,
    val detail: String? = null,
)

sealed interface Compatibility {
    data object Compatible : Compatibility

    data class Incompatible(
        val reasons: List<AssessmentReason>,
        val evidence: List<Evidence> = emptyList(),
    ) : Compatibility

    data class Unknown(
        val reasons: List<AssessmentReason>,
        val evidence: List<Evidence> = emptyList(),
    ) : Compatibility
}

enum class FitBand {
    COMFORTABLE,
    LIKELY,
    BORDERLINE,
    NO_FIT,
}

/** A stable identity implemented by executable plans introduced by the planning layer. */
interface PlanReference {
    val stableKey: String
}

data class PlanAssessment(
    val plan: PlanReference,
    val hostMemoryBytes: EstimateRange?,
    val gpuMemoryBytes: EstimateRange?,
    val sharedMemoryBytes: EstimateRange?,
    val storageBytes: EstimateRange?,
    val confidence: AssessmentConfidence,
    val evidence: List<Evidence>,
)

data class AssessedPlans(
    val values: List<PlanAssessment>,
)

data class ModelAssessment(
    val assessmentKey: String,
    val compatibility: Compatibility,
    val planAssessments: AssessedPlans,
    val baseHostBudgetBytes: Long?,
    val baseGpuBudgetBytes: Long?,
    val baseSharedBudgetBytes: Long?,
    val baseStorageBudgetBytes: Long?,
    val confidence: AssessmentConfidence,
    val evidence: List<Evidence>,
)

data class PersonalizedRecommendation(
    val assessmentKey: String,
    val category: RecommendationCategory,
    val selectedPlan: PlanReference?,
    val reasons: List<AssessmentReason>,
    val profile: RecommendationProfile,
)
