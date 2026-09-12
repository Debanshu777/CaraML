package com.debanshu777.caraml.features.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.debanshu777.caraml.core.settings.AppSettings
import com.debanshu777.caraml.core.settings.KvQuantPreset
import com.debanshu777.caraml.core.data.settings.SettingsRepository
import com.debanshu777.caraml.core.recommendation.OptimizationPriority
import com.debanshu777.caraml.core.recommendation.RecommendationProfile
import com.debanshu777.caraml.core.recommendation.RiskTolerance
import com.debanshu777.caraml.core.recommendation.CalibrationRunResult
import com.debanshu777.caraml.core.recommendation.QuickCalibrationRunner
import com.debanshu777.caraml.features.modelhub.presentation.search.QuickCalibrationUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SettingsViewModel(
    private val repository: SettingsRepository,
    private val quickCalibrationRunner: QuickCalibrationRunner? = null,
) : ViewModel() {

    private val queuedProfile = MutableStateFlow<RecommendationProfile?>(null)
    private val optimisticProfile = MutableStateFlow<RecommendationProfile?>(null)
    private val profileWriteMutex = Mutex()
    private val onboardingSubmissionInFlight = MutableStateFlow(false)
    private val onboardingCompletionCommitted = MutableStateFlow(false)
    private val _settingsLoaded = MutableStateFlow(false)
    private val _isRecommendationProfileSaving = MutableStateFlow(false)
    private val _recommendationProfileError = MutableStateFlow<String?>(null)
    private val _quickCalibration = MutableStateFlow(QuickCalibrationUiState())
    val quickCalibration: StateFlow<QuickCalibrationUiState> = _quickCalibration.asStateFlow()
    private var calibrationJob: Job? = null

    val settings = repository.getSettings()
        .onEach { persisted ->
            _settingsLoaded.value = true
            if (persisted.modelProfileOnboardingComplete) {
                onboardingCompletionCommitted.value = true
            }
            val optimistic = optimisticProfile.value
            if (queuedProfile.value == null && persisted.recommendationProfile == optimistic) {
                optimisticProfile.value = null
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AppSettings()
        )

    val effectiveRecommendationProfile: StateFlow<RecommendationProfile> =
        combine(settings, optimisticProfile) { persisted, optimistic ->
            optimistic ?: persisted.recommendationProfile
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AppSettings().recommendationProfile,
        )

    val settingsLoaded: StateFlow<Boolean> = _settingsLoaded.asStateFlow()

    val isRecommendationProfileSaving: StateFlow<Boolean> =
        _isRecommendationProfileSaving.asStateFlow()

    val recommendationProfileError: StateFlow<String?> =
        _recommendationProfileError.asStateFlow()

    fun updateSystemPrompt(systemPrompt: String) {
        viewModelScope.launch {
            repository.updateSettings(settings.value.copy(systemPrompt = systemPrompt))
        }
    }

    fun updateTemperature(temperature: Float) {
        val clamped = temperature.coerceIn(0f, 2f)
        viewModelScope.launch {
            repository.updateSettings(settings.value.copy(temperature = clamped))
        }
    }

    fun updateKvQuantPreset(preset: KvQuantPreset) {
        viewModelScope.launch {
            repository.updateSettings(settings.value.copy(kvQuantPreset = preset))
        }
    }

    fun updateUseGpu(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateSettings(settings.value.copy(useGpu = enabled))
        }
    }

    fun updateRecommendationProfile(profile: RecommendationProfile) {
        queuedProfile.value = profile
        optimisticProfile.value = profile
        _recommendationProfileError.value = null
        _isRecommendationProfileSaving.value = true
        viewModelScope.launch { drainProfileUpdates() }
    }

    fun updateRiskTolerance(riskTolerance: RiskTolerance) {
        updateRecommendationProfile(
            latestRecommendationProfile().copy(riskTolerance = riskTolerance),
        )
    }

    fun updateOptimizationPriority(optimizationPriority: OptimizationPriority) {
        updateRecommendationProfile(
            latestRecommendationProfile().copy(optimizationPriority = optimizationPriority),
        )
    }

    fun completeModelProfileOnboarding(profile: RecommendationProfile) {
        if (onboardingSubmissionInFlight.value || onboardingCompletionCommitted.value) return
        onboardingSubmissionInFlight.value = true
        optimisticProfile.value = profile
        _recommendationProfileError.value = null
        _isRecommendationProfileSaving.value = true
        viewModelScope.launch {
            try {
                profileWriteMutex.withLock {
                    repository.completeModelProfileOnboarding(profile)
                }
                onboardingCompletionCommitted.value = true
            } catch (cancellation: CancellationException) {
                restorePersistedProfile(profile)
                onboardingCompletionCommitted.value = false
                throw cancellation
            } catch (_: Exception) {
                restorePersistedProfile(profile)
                onboardingCompletionCommitted.value = false
                _recommendationProfileError.value = PROFILE_SAVE_ERROR
            } finally {
                onboardingSubmissionInFlight.value = false
                _isRecommendationProfileSaving.value = queuedProfile.value != null
            }
        }
    }

    fun runQuickCalibration(allowUnknownPower: Boolean = false) {
        val calibration = quickCalibrationRunner ?: return
        if (_quickCalibration.value.running) return
        _quickCalibration.value = QuickCalibrationUiState(running = true)
        calibrationJob = viewModelScope.launch {
            try {
                _quickCalibration.value = QuickCalibrationUiState(
                    result = calibration.runQuickCalibration(allowUnknownPower),
                )
            } catch (cancelled: CancellationException) {
                _quickCalibration.value = QuickCalibrationUiState(
                    result = CalibrationRunResult.Cancelled,
                )
                throw cancelled
            }
        }
    }

    fun cancelQuickCalibration() {
        calibrationJob?.cancel()
    }

    fun skipQuickCalibration() {
        val calibration = quickCalibrationRunner ?: return
        calibrationJob?.cancel()
        viewModelScope.launch {
            try {
                calibration.skipQuickCalibration()
                _quickCalibration.value = QuickCalibrationUiState()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _quickCalibration.value = QuickCalibrationUiState(
                    result = CalibrationRunResult.Failed,
                )
            }
        }
    }

    private suspend fun drainProfileUpdates() {
        profileWriteMutex.withLock {
            while (true) {
                val profile = queuedProfile.value ?: break
                queuedProfile.value = null
                try {
                    repository.updateRecommendationProfile(profile)
                } catch (cancellation: CancellationException) {
                    restorePersistedProfile(profile)
                    throw cancellation
                } catch (_: Exception) {
                    if (queuedProfile.value == null) {
                        restorePersistedProfile(profile)
                        _recommendationProfileError.value = PROFILE_SAVE_ERROR
                    }
                }
            }
        }
        _isRecommendationProfileSaving.value = onboardingSubmissionInFlight.value
    }

    private fun restorePersistedProfile(failedProfile: RecommendationProfile) {
        if (optimisticProfile.value == failedProfile && queuedProfile.value == null) {
            optimisticProfile.value = null
        }
    }

    private fun latestRecommendationProfile(): RecommendationProfile =
        queuedProfile.value ?: optimisticProfile.value ?: settings.value.recommendationProfile

    private companion object {
        const val PROFILE_SAVE_ERROR = "Could not save the recommendation profile. Try again."
    }
}
