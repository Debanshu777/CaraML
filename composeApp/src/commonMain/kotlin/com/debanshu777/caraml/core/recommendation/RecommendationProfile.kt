package com.debanshu777.caraml.core.recommendation

enum class RiskTolerance {
    CONSERVATIVE,
    BALANCED,
    EXPERIMENTAL,
}

enum class OptimizationPriority {
    SPEED_EFFICIENCY,
    BALANCED,
    QUALITY_CONTEXT,
}

data class RecommendationProfile(
    val riskTolerance: RiskTolerance = RiskTolerance.BALANCED,
    val optimizationPriority: OptimizationPriority = OptimizationPriority.BALANCED,
)

enum class RecommendationCategory {
    RECOMMENDED,
    USABLE,
    RISKY,
    NOT_SUITABLE,
    INCOMPATIBLE,
    NEEDS_INFORMATION,
}

internal object RecommendationPolicyV1 {
    val memoryReservePercent: Map<RiskTolerance, Int> = mapOf(
        RiskTolerance.CONSERVATIVE to 25,
        RiskTolerance.BALANCED to 15,
        RiskTolerance.EXPERIMENTAL to 5,
    )
    val llmDecodeTarget: Map<OptimizationPriority, Double> = mapOf(
        OptimizationPriority.SPEED_EFFICIENCY to 8.0,
        OptimizationPriority.BALANCED to 4.0,
        OptimizationPriority.QUALITY_CONTEXT to 2.0,
    )
    val llmDecodeHardMinimum: Map<OptimizationPriority, Double> = mapOf(
        OptimizationPriority.SPEED_EFFICIENCY to 4.0,
        OptimizationPriority.BALANCED to 2.0,
        OptimizationPriority.QUALITY_CONTEXT to 1.0,
    )
    val diffusionSecondsTarget: Map<OptimizationPriority, Double> = mapOf(
        OptimizationPriority.SPEED_EFFICIENCY to 30.0,
        OptimizationPriority.BALANCED to 90.0,
        OptimizationPriority.QUALITY_CONTEXT to 180.0,
    )
    val diffusionSecondsHardMaximum: Map<OptimizationPriority, Double> = mapOf(
        OptimizationPriority.SPEED_EFFICIENCY to 60.0,
        OptimizationPriority.BALANCED to 180.0,
        OptimizationPriority.QUALITY_CONTEXT to 360.0,
    )
    const val RESOURCE_SNAPSHOT_MAX_AGE_MS: Long = 30_000L
    const val NATIVE_PREFLIGHT_MAX_AGE_MS: Long = 30_000L
    const val MAX_SERIALIZED_PERFORMANCE_VALUE: Double = 31_536_000.0
    const val MAX_SERIALIZED_RATE: Double = 1_000_000_000.0

    val categoryRank: Map<RecommendationCategory, Int> = mapOf(
        RecommendationCategory.RECOMMENDED to 0,
        RecommendationCategory.USABLE to 1,
        RecommendationCategory.RISKY to 2,
        RecommendationCategory.NEEDS_INFORMATION to 3,
        RecommendationCategory.NOT_SUITABLE to 4,
        RecommendationCategory.INCOMPATIBLE to 5,
    )
}
