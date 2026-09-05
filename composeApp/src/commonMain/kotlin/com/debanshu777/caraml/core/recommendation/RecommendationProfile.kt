package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.rating.SdArchitecture

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
    data class UtilityWeights(
        val performance: Double,
        val energy: Double,
        val quality: Double,
        val context: Double,
        val storage: Double,
    )

    data class QualityProxyWeights(
        val quantization: Double,
        val scale: Double,
    )

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
    val utilityWeights: Map<OptimizationPriority, UtilityWeights> = mapOf(
        OptimizationPriority.SPEED_EFFICIENCY to UtilityWeights(0.60, 0.25, 0.05, 0.00, 0.10),
        OptimizationPriority.BALANCED to UtilityWeights(0.30, 0.10, 0.30, 0.20, 0.10),
        OptimizationPriority.QUALITY_CONTEXT to UtilityWeights(0.10, 0.05, 0.55, 0.25, 0.05),
    )
    val llmQuantizationQualityProxy: Map<String, Double> = mapOf(
        "F32" to 1.00,
        "FP32" to 1.00,
        "F16" to 0.98,
        "FP16" to 0.98,
        "BF16" to 0.98,
        "Q8_0" to 0.92,
        "INT8" to 0.92,
        "Q6_K" to 0.88,
        "Q5_K_M" to 0.82,
        "Q5_K_S" to 0.80,
        "Q4_K_M" to 0.72,
        "Q4_K_S" to 0.70,
        "Q4_0" to 0.65,
        "INT4" to 0.65,
    )
    val diffusionArchitectureQualityProxy: Map<SdArchitecture, Double> = mapOf(
        SdArchitecture.SD1 to 0.65,
        SdArchitecture.SDXL to 0.80,
        SdArchitecture.SD3 to 0.90,
        SdArchitecture.FLUX to 1.00,
        SdArchitecture.WAN_SMALL to 0.80,
        SdArchitecture.WAN_LARGE to 1.00,
    )
    val diffusionQuantizationQualityProxy: Map<String, Double> = llmQuantizationQualityProxy
    val llmQualityProxyWeights = QualityProxyWeights(quantization = 0.50, scale = 0.35)
    val diffusionQualityProxyWeights = QualityProxyWeights(quantization = 0.35, scale = 0.65)

    const val LLM_PARAMETER_QUALITY_TARGET: Long = 13_000_000_000L
    const val STORAGE_EFFICIENCY_TARGET_BYTES: Long = 1_073_741_824L
    const val UTILITY_METRIC_MIN: Double = 0.05
    const val UTILITY_METRIC_MAX: Double = 1.0
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
