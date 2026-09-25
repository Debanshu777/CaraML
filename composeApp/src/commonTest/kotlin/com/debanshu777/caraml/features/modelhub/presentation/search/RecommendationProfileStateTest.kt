package com.debanshu777.caraml.features.modelhub.presentation.search

import com.debanshu777.caraml.core.data.settings.SettingsRepository
import com.debanshu777.caraml.core.recommendation.OptimizationPriority
import com.debanshu777.caraml.core.recommendation.RecommendationProfile
import com.debanshu777.caraml.core.recommendation.RecommendationRolloutMode
import com.debanshu777.caraml.core.recommendation.RiskTolerance
import com.debanshu777.caraml.core.settings.AppSettings
import com.debanshu777.caraml.features.settings.presentation.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RecommendationProfileStateTest {

    @Test
    fun legacyNeverExposesProfileUiOrOnboarding() {
        val state = profileUiState(
            settings = settings(onboardingComplete = false),
            rolloutMode = RecommendationRolloutMode.LEGACY,
        )

        assertFalse(state.isAvailable)
        assertFalse(state.showDialog)
    }

    @Test
    fun dialogWaitsForPersistedSettingsBeforeFirstPresentation() {
        val state = profileUiState(
            settings = settings(onboardingComplete = false),
            rolloutMode = RecommendationRolloutMode.SHADOW,
            settingsLoaded = false,
        )

        assertFalse(state.isAvailable)
        assertFalse(state.showDialog)
    }

    @Test
    fun shadowAndV2ExposeProfileUiAndGateDialogOnPersistedCompletion() {
        listOf(RecommendationRolloutMode.SHADOW, RecommendationRolloutMode.V2).forEach { mode ->
            val incomplete = profileUiState(settings(false), mode)
            val complete = profileUiState(settings(true), mode)

            assertTrue(incomplete.isAvailable, mode.name)
            assertTrue(incomplete.showDialog, mode.name)
            assertTrue(complete.isAvailable, mode.name)
            assertFalse(complete.showDialog, mode.name)
        }
    }

    @Test
    fun dialogShowsOnlyUntilOnboardingIsCompleted() {
        val before = profileUiState(
            settings = settings(onboardingComplete = false),
            rolloutMode = RecommendationRolloutMode.SHADOW,
        )
        val after = profileUiState(
            settings = settings(onboardingComplete = true),
            rolloutMode = RecommendationRolloutMode.SHADOW,
        )

        assertTrue(before.showDialog)
        assertFalse(after.showDialog)
    }

    @Test
    fun rapidProfileUpdatesPersistTheNewestSelection() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val repository = FakeSettingsRepository(updateDelayMillis = 10)
            val viewModel = SettingsViewModel(repository)
            val conservative = RecommendationProfile(
                riskTolerance = RiskTolerance.CONSERVATIVE,
                optimizationPriority = OptimizationPriority.BALANCED,
            )
            val newest = RecommendationProfile(
                riskTolerance = RiskTolerance.EXPERIMENTAL,
                optimizationPriority = OptimizationPriority.QUALITY_CONTEXT,
            )

            viewModel.updateRecommendationProfile(conservative)
            viewModel.updateRecommendationProfile(newest)
            advanceUntilIdle()

            assertEquals(newest, repository.settings.value.recommendationProfile)
            assertEquals(newest, repository.profileUpdates.last())
            assertFalse(viewModel.isRecommendationProfileSaving.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun rapidRiskAndPriorityClicksMergeAgainstTheLatestOptimisticProfile() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val repository = FakeSettingsRepository(updateDelayMillis = 10)
            val viewModel = SettingsViewModel(repository)

            viewModel.updateRiskTolerance(RiskTolerance.EXPERIMENTAL)
            viewModel.updateOptimizationPriority(OptimizationPriority.QUALITY_CONTEXT)
            advanceUntilIdle()

            assertEquals(
                RecommendationProfile(
                    riskTolerance = RiskTolerance.EXPERIMENTAL,
                    optimizationPriority = OptimizationPriority.QUALITY_CONTEXT,
                ),
                repository.settings.value.recommendationProfile,
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun repeatedCompletionIntentRunsOneAtomicRepositoryIntent() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val repository = FakeSettingsRepository(updateDelayMillis = 10)
            val viewModel = SettingsViewModel(repository)
            val profile = RecommendationProfile(
                riskTolerance = RiskTolerance.CONSERVATIVE,
                optimizationPriority = OptimizationPriority.SPEED_EFFICIENCY,
            )

            viewModel.completeModelProfileOnboarding(profile)
            viewModel.completeModelProfileOnboarding(profile)
            advanceUntilIdle()
            viewModel.completeModelProfileOnboarding(profile)
            advanceUntilIdle()

            assertEquals(listOf(profile), repository.completedProfiles)
            assertTrue(repository.settings.value.modelProfileOnboardingComplete)
            assertFalse(viewModel.isRecommendationProfileSaving.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun failedProfileWriteRestoresPersistedStateAndReportsGenericError() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val repository = FakeSettingsRepository(failProfileUpdates = true)
            val viewModel = SettingsViewModel(repository)

            viewModel.updateRecommendationProfile(
                RecommendationProfile(
                    riskTolerance = RiskTolerance.EXPERIMENTAL,
                    optimizationPriority = OptimizationPriority.QUALITY_CONTEXT,
                ),
            )
            advanceUntilIdle()

            assertEquals(RecommendationProfile(), repository.settings.value.recommendationProfile)
            assertFalse(viewModel.isRecommendationProfileSaving.value)
            assertNotNull(viewModel.recommendationProfileError.value)
            assertFalse(
                viewModel.recommendationProfileError.value.orEmpty().contains("sensitive"),
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun settings(onboardingComplete: Boolean): AppSettings = AppSettings(
        modelProfileOnboardingComplete = onboardingComplete,
    )

    private class FakeSettingsRepository(
        private val updateDelayMillis: Long = 0,
        private val failProfileUpdates: Boolean = false,
    ) : SettingsRepository {
        val settings = MutableStateFlow(AppSettings())
        val profileUpdates = mutableListOf<RecommendationProfile>()
        val completedProfiles = mutableListOf<RecommendationProfile>()

        override fun getSettings(): Flow<AppSettings> = settings

        override suspend fun updateSettings(settings: AppSettings) {
            this.settings.value = settings
        }

        override suspend fun updateRecommendationProfile(profile: RecommendationProfile) {
            delay(updateDelayMillis)
            if (failProfileUpdates) error("sensitive internal failure")
            profileUpdates += profile
            settings.value = settings.value.copy(
                riskTolerance = profile.riskTolerance,
                optimizationPriority = profile.optimizationPriority,
            )
        }

        override suspend fun completeModelProfileOnboarding(profile: RecommendationProfile) {
            delay(updateDelayMillis)
            completedProfiles += profile
            settings.value = settings.value.copy(
                riskTolerance = profile.riskTolerance,
                optimizationPriority = profile.optimizationPriority,
                modelProfileOnboardingComplete = true,
            )
        }
    }
}
