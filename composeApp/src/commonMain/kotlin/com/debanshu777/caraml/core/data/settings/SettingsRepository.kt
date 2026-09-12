package com.debanshu777.caraml.core.data.settings

import com.debanshu777.caraml.core.settings.AppSettings
import com.debanshu777.caraml.core.recommendation.RecommendationProfile
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    fun getSettings(): Flow<AppSettings>
    suspend fun updateSettings(settings: AppSettings)
    suspend fun updateRecommendationProfile(profile: RecommendationProfile)
    suspend fun completeModelProfileOnboarding(profile: RecommendationProfile)
    suspend fun completeRecommendationCalibrationOffer() = Unit
}
