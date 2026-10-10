package com.debanshu777.caraml.core.theme

import androidx.compose.ui.graphics.Color
import com.debanshu777.caraml.core.data.theme.ThemeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ThemeViewModelTest {
    @Test
    fun quickAppearanceChangesPreserveEachOtherAndTheStoredSeedWithoutACollector() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val stored = MutableStateFlow(ThemePreferences(seedColor = Color.Blue, themeMode = ThemeMode.DARK))
            val repository = object : ThemeRepository {
                override fun getPreferences() = stored
                override suspend fun updatePreferences(preferences: ThemePreferences) {
                    delay(10)
                    stored.value = preferences
                }
            }
            val viewModel = ThemeViewModel(repository)

            viewModel.updateReduceMotion(true)
            viewModel.updateSoftEffects(false)
            advanceUntilIdle()

            assertTrue(stored.value.reduceMotion)
            assertFalse(stored.value.softEffects)
            assertEquals(Color.Blue, stored.value.seedColor)
            assertEquals(ThemeMode.DARK, stored.value.themeMode)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
