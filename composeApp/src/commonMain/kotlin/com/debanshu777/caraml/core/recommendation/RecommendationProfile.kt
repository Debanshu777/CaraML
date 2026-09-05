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
