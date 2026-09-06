package com.debanshu777.caraml.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.debanshu777.caraml.core.recommendation.OptimizationPriority
import com.debanshu777.caraml.core.recommendation.RecommendationProfile
import com.debanshu777.caraml.core.recommendation.RiskTolerance
import com.debanshu777.caraml.core.settings.AppSettings
import com.debanshu777.caraml.core.settings.KvQuantPreset
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefaultSettingsRepositoryTest {

    @Test
    fun unknownPreferenceNamesFallBackToBalanced() = runTest {
        val fixture = fixture()
        fixture.store.edit { preferences ->
            preferences[stringPreferencesKey("model_risk_tolerance")] = "REMOVED_VALUE"
            preferences[stringPreferencesKey("model_optimization_priority")] = "OLD_VALUE"
        }

        val settings = fixture.repository.getSettings().first()

        assertEquals(RiskTolerance.BALANCED, settings.riskTolerance)
        assertEquals(OptimizationPriority.BALANCED, settings.optimizationPriority)
    }

    @Test
    fun onboardingDefaultsToIncomplete() = runTest {
        val settings = fixture().repository.getSettings().first()

        assertFalse(settings.modelProfileOnboardingComplete)
        assertEquals(RecommendationProfile(), settings.recommendationProfile)
    }

    @Test
    fun balancedDismissCompletionWritesProfileAndFlagInOneUpdate() = runTest {
        val fixture = fixture()
        fixture.store.edit { preferences ->
            preferences[stringPreferencesKey("unrelated_setting")] = "keep-me"
        }
        fixture.store.updateCount = 0

        fixture.repository.completeModelProfileOnboarding(RecommendationProfile())

        val preferences = fixture.store.data.first()
        assertEquals(1, fixture.store.updateCount)
        assertEquals("BALANCED", preferences[stringPreferencesKey("model_risk_tolerance")])
        assertEquals("BALANCED", preferences[stringPreferencesKey("model_optimization_priority")])
        assertTrue(preferences[booleanPreferencesKey("model_profile_onboarding_complete")] == true)
        assertEquals("keep-me", preferences[stringPreferencesKey("unrelated_setting")])
    }

    @Test
    fun updatingProfilePreservesUnrelatedSettings() = runTest {
        val fixture = fixture()
        fixture.repository.updateSettings(
            AppSettings(
                systemPrompt = "Keep this prompt",
                temperature = 1.4f,
                kvQuantPreset = KvQuantPreset.Q8_Q8,
                useGpu = false,
            ),
        )
        fixture.store.edit { preferences ->
            preferences[stringPreferencesKey("future_setting")] = "preserved"
        }
        fixture.store.updateCount = 0

        fixture.repository.updateRecommendationProfile(
            RecommendationProfile(
                riskTolerance = RiskTolerance.EXPERIMENTAL,
                optimizationPriority = OptimizationPriority.QUALITY_CONTEXT,
            ),
        )

        val settings = fixture.repository.getSettings().first()
        val preferences = fixture.store.data.first()
        assertEquals(1, fixture.store.updateCount)
        assertEquals("Keep this prompt", settings.systemPrompt)
        assertEquals(1.4f, settings.temperature)
        assertEquals(KvQuantPreset.Q8_Q8, settings.kvQuantPreset)
        assertFalse(settings.useGpu)
        assertFalse(settings.modelProfileOnboardingComplete)
        assertEquals(RiskTolerance.EXPERIMENTAL, settings.riskTolerance)
        assertEquals(OptimizationPriority.QUALITY_CONTEXT, settings.optimizationPriority)
        assertEquals("preserved", preferences[stringPreferencesKey("future_setting")])
    }

    private fun TestScope.fixture(): Fixture {
        val directory = Files.createTempDirectory("caraml-settings-test").toFile().apply {
            deleteOnExit()
        }
        val path = directory.resolve("settings.preferences_pb").apply { deleteOnExit() }
        val delegate = PreferenceDataStoreFactory.createWithPath(scope = backgroundScope) {
            path.absolutePath.toPath()
        }
        val store = RecordingPreferencesDataStore(delegate)
        return Fixture(
            store = store,
            repository = DefaultSettingsRepository(store),
        )
    }

    private data class Fixture(
        val store: RecordingPreferencesDataStore,
        val repository: DefaultSettingsRepository,
    )

    private class RecordingPreferencesDataStore(
        private val delegate: DataStore<Preferences>,
    ) : DataStore<Preferences> {
        var updateCount: Int = 0

        override val data: Flow<Preferences> = delegate.data

        override suspend fun updateData(
            transform: suspend (Preferences) -> Preferences,
        ): Preferences {
            updateCount += 1
            return delegate.updateData(transform)
        }
    }
}
