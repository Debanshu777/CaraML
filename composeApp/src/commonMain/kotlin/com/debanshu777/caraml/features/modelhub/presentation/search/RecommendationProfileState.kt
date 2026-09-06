package com.debanshu777.caraml.features.modelhub.presentation.search

import com.debanshu777.caraml.core.recommendation.RecommendationProfile
import com.debanshu777.caraml.core.recommendation.RecommendationRolloutMode
import com.debanshu777.caraml.core.settings.AppSettings

data class RecommendationProfileUiState(
    val profile: RecommendationProfile,
    val isAvailable: Boolean,
    val showDialog: Boolean,
)

fun profileUiState(
    settings: AppSettings,
    rolloutMode: RecommendationRolloutMode,
    settingsLoaded: Boolean = true,
): RecommendationProfileUiState {
    val isAvailable = settingsLoaded && rolloutMode != RecommendationRolloutMode.LEGACY
    return RecommendationProfileUiState(
        profile = settings.recommendationProfile,
        isAvailable = isAvailable,
        showDialog = isAvailable && !settings.modelProfileOnboardingComplete,
    )
}
