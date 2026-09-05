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

    @ConsistentCopyVisibility
    data class Incompatible private constructor(
        val reasons: List<AssessmentReason>,
        val evidence: List<Evidence>,
    ) : Compatibility {
        constructor(
            reasons: Collection<AssessmentReason>,
            evidence: Collection<Evidence> = emptyList(),
        ) : this(reasons.toList(), evidence.toList())
    }

    @ConsistentCopyVisibility
    data class Unknown private constructor(
        val reasons: List<AssessmentReason>,
        val evidence: List<Evidence>,
    ) : Compatibility {
        constructor(
            reasons: Collection<AssessmentReason>,
            evidence: Collection<Evidence> = emptyList(),
        ) : this(reasons.toList(), evidence.toList())
    }
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

@ConsistentCopyVisibility
data class PlanAssessment private constructor(
    val plan: PlanReference,
    val hostMemoryBytes: EstimateRange?,
    val gpuMemoryBytes: EstimateRange?,
    val sharedMemoryBytes: EstimateRange?,
    val storageBytes: EstimateRange?,
    val confidence: AssessmentConfidence,
    val evidence: List<Evidence>,
) {
    constructor(
        plan: PlanReference,
        hostMemoryBytes: EstimateRange?,
        gpuMemoryBytes: EstimateRange?,
        sharedMemoryBytes: EstimateRange?,
        storageBytes: EstimateRange?,
        confidence: AssessmentConfidence,
        evidence: Collection<Evidence>,
    ) : this(
        plan = plan,
        hostMemoryBytes = hostMemoryBytes,
        gpuMemoryBytes = gpuMemoryBytes,
        sharedMemoryBytes = sharedMemoryBytes,
        storageBytes = storageBytes,
        confidence = confidence,
        evidence = evidence.toList(),
    )
}

@ConsistentCopyVisibility
data class AssessedPlans private constructor(
    val values: List<PlanAssessment>,
) {
    constructor(values: Collection<PlanAssessment>) : this(values.toList())
}

@ConsistentCopyVisibility
data class ModelAssessment private constructor(
    val assessmentKey: String,
    val compatibility: Compatibility,
    val planAssessments: AssessedPlans,
    val baseHostBudgetBytes: Long?,
    val baseGpuBudgetBytes: Long?,
    val baseSharedBudgetBytes: Long?,
    val baseStorageBudgetBytes: Long?,
    val confidence: AssessmentConfidence,
    val evidence: List<Evidence>,
) {
    constructor(
        assessmentKey: String,
        compatibility: Compatibility,
        planAssessments: AssessedPlans,
        baseHostBudgetBytes: Long?,
        baseGpuBudgetBytes: Long?,
        baseSharedBudgetBytes: Long?,
        baseStorageBudgetBytes: Long?,
        confidence: AssessmentConfidence,
        evidence: Collection<Evidence>,
    ) : this(
        assessmentKey = assessmentKey,
        compatibility = compatibility,
        planAssessments = planAssessments,
        baseHostBudgetBytes = baseHostBudgetBytes,
        baseGpuBudgetBytes = baseGpuBudgetBytes,
        baseSharedBudgetBytes = baseSharedBudgetBytes,
        baseStorageBudgetBytes = baseStorageBudgetBytes,
        confidence = confidence,
        evidence = evidence.toList(),
    )
}

@ConsistentCopyVisibility
data class PersonalizedRecommendation private constructor(
    val assessmentKey: String,
    val category: RecommendationCategory,
    val selectedPlan: PlanReference?,
    val reasons: List<AssessmentReason>,
    val profile: RecommendationProfile,
) {
    constructor(
        assessmentKey: String,
        category: RecommendationCategory,
        selectedPlan: PlanReference?,
        reasons: Collection<AssessmentReason>,
        profile: RecommendationProfile,
    ) : this(
        assessmentKey = assessmentKey,
        category = category,
        selectedPlan = selectedPlan,
        reasons = reasons.toList(),
        profile = profile,
    )
}
