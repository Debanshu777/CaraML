package com.debanshu777.caraml.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.debanshu777.caraml.core.recommendation.OptimizationPriority
import com.debanshu777.caraml.core.recommendation.RecommendationProfile
import com.debanshu777.caraml.core.recommendation.RiskTolerance
import com.debanshu777.caraml.core.settings.AppSettings
import com.debanshu777.caraml.core.settings.KvQuantPreset
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

class DefaultSettingsRepository(
    private val dataStore: DataStore<Preferences>,
    private val historyDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : SettingsRepository {

    private val systemPromptKey = stringPreferencesKey("system_prompt")
    private val temperatureKey = floatPreferencesKey("temperature")
    private val kvQuantPresetKey = stringPreferencesKey("kv_quant_preset")
    private val useGpuKey = booleanPreferencesKey("use_gpu")
    private val riskToleranceKey = stringPreferencesKey("model_risk_tolerance")
    private val optimizationPriorityKey = stringPreferencesKey("model_optimization_priority")
    private val modelProfileOnboardingCompleteKey =
        booleanPreferencesKey("model_profile_onboarding_complete")
    private val recommendationCalibrationOfferCompleteKey =
        booleanPreferencesKey("recommendation_calibration_offer_complete")
    private val recentlyViewedModelsKey = stringPreferencesKey("modelhub_recently_viewed_models")
    private val recoveredHistoryKey = stringPreferencesKey("modelhub_recently_viewed_models_recovery")
    private val historyCodec = RecentModelHistoryCodec()

    override fun getSettings(): Flow<AppSettings> = dataStore.data.map(::readSettings)

    override fun getRecentlyViewedModels(): Flow<List<RecentlyViewedModel>> = dataStore.data
        .map { it[recentlyViewedModelsKey] }
        .distinctUntilChanged()
        .map { encoded ->
            val decoded = encoded?.let(historyCodec::decode)
            if (decoded?.legacy == true) {
                val compact = historyCodec.encode(decoded.models)
                try {
                    dataStore.edit { prefs ->
                        // A newer visit may have committed while this snapshot was decoded.
                        if (prefs[recentlyViewedModelsKey] == encoded) prefs[recentlyViewedModelsKey] = compact
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A failed optional migration must not hide readable history.
                }
            }
            decoded?.models.orEmpty()
        }
        .flowOn(historyDispatcher)
        .distinctUntilChanged()

    override suspend fun recordRecentlyViewedModel(model: RecentlyViewedModel) {
        if (model.modelId.isBlank()) return
        withContext(historyDispatcher) {
            dataStore.edit { prefs ->
                val encoded = prefs[recentlyViewedModelsKey]
                val existing = encoded?.let(historyCodec::decode)
                // A new visit repairs history while preserving the first unreadable value for recovery.
                if (encoded != null && existing == null && prefs[recoveredHistoryKey] == null) {
                    prefs[recoveredHistoryKey] = encoded
                }
                prefs[recentlyViewedModelsKey] = historyCodec.encode(
                    prependRecentlyViewed(existing?.models.orEmpty(), model),
                )
            }
        }
    }

    private fun readSettings(prefs: Preferences): AppSettings =
        AppSettings(
            systemPrompt = prefs[systemPromptKey]
                ?: AppSettings.Companion.DEFAULT_SYSTEM_PROMPT,
            temperature = prefs[temperatureKey] ?: AppSettings.Companion.DEFAULT_TEMPERATURE,
            kvQuantPreset = prefs[kvQuantPresetKey]
                ?.let { name -> runCatching { KvQuantPreset.valueOf(name) }.getOrNull() }
                ?: KvQuantPreset.AUTO,
            useGpu = prefs[useGpuKey] ?: true,
            riskTolerance = prefs[riskToleranceKey]
                ?.let { name -> RiskTolerance.entries.firstOrNull { it.name == name } }
                ?: RiskTolerance.BALANCED,
            optimizationPriority = prefs[optimizationPriorityKey]
                ?.let { name -> OptimizationPriority.entries.firstOrNull { it.name == name } }
                ?: OptimizationPriority.BALANCED,
            modelProfileOnboardingComplete =
                prefs[modelProfileOnboardingCompleteKey] ?: false,
            recommendationCalibrationOfferComplete =
                prefs[recommendationCalibrationOfferCompleteKey] ?: false,
        )

    override suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { prefs ->
            val settings = transform(readSettings(prefs))
            prefs[systemPromptKey] = settings.systemPrompt
            prefs[temperatureKey] = settings.temperature
            prefs[kvQuantPresetKey] = settings.kvQuantPreset.name
            prefs[useGpuKey] = settings.useGpu
        }
    }

    override suspend fun updateRecommendationProfile(profile: RecommendationProfile) {
        dataStore.edit { prefs ->
            prefs[riskToleranceKey] = profile.riskTolerance.name
            prefs[optimizationPriorityKey] = profile.optimizationPriority.name
        }
    }

    override suspend fun completeModelProfileOnboarding(profile: RecommendationProfile) {
        dataStore.edit { prefs ->
            prefs[riskToleranceKey] = profile.riskTolerance.name
            prefs[optimizationPriorityKey] = profile.optimizationPriority.name
            prefs[modelProfileOnboardingCompleteKey] = true
        }
    }

    override suspend fun completeRecommendationCalibrationOffer() {
        dataStore.edit { prefs ->
            prefs[recommendationCalibrationOfferCompleteKey] = true
        }
    }
}
