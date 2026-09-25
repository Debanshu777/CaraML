package com.debanshu777.caraml.core.recommendation

enum class RecommendationRolloutMode {
    LEGACY,
    SHADOW,
    V2,
}

fun interface RecommendationRolloutModeSource {
    fun current(): RecommendationRolloutMode
}

/**
 * Build integrations inject [isDebugBuild]. The no-argument default is intentionally release-safe
 * until the Task 15 rollout gate promotes v2.
 */
class DefaultRecommendationRolloutModeSource(
    isDebugBuild: Boolean = false,
) : RecommendationRolloutModeSource {
    private val mode = if (isDebugBuild) {
        RecommendationRolloutMode.SHADOW
    } else {
        RecommendationRolloutMode.LEGACY
    }

    override fun current(): RecommendationRolloutMode = mode
}
