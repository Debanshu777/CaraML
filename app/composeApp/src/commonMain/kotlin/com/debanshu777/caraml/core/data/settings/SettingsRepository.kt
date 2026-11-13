package com.debanshu777.caraml.core.data.settings

import com.debanshu777.caraml.core.settings.AppSettings
import com.debanshu777.caraml.core.recommendation.RecommendationProfile
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    fun getSettings(): Flow<AppSettings>
    /** Apply a change to the latest inference settings in the same transaction that persists it. */
    suspend fun updateSettings(transform: (AppSettings) -> AppSettings)
    suspend fun updateRecommendationProfile(profile: RecommendationProfile)
    suspend fun completeModelProfileOnboarding(profile: RecommendationProfile)
    suspend fun completeRecommendationCalibrationOffer() = Unit
}
