@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.core.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CaraMLThemeResolvedModeTest {

    @Test
    fun reportsEffectiveDarkThemeWhenPreferenceChangesAtRuntime() = runComposeUiTest {
        var preferences by mutableStateOf(ThemePreferences(themeMode = ThemeMode.LIGHT))
        val observedModes = mutableListOf<Boolean>()

        setContent {
            CaraMLTheme(
                preferences = preferences,
                onEffectiveDarkThemeChanged = { observedModes += it },
            ) {}
        }

        waitForIdle()
        assertEquals(listOf(false), observedModes)

        runOnIdle {
            preferences = preferences.copy(themeMode = ThemeMode.DARK)
        }
        waitForIdle()

        assertEquals(listOf(false, true), observedModes)
    }
}
