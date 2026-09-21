@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.core.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CaraMLThemeResolvedModeTest {

    @Test
    fun selectedControlsUseTheExactSeedAsTheAppPrimaryWithReadableContent() =
        runComposeUiTest {
            val seed = Color(0xFFEFD04B)
            var resolvedPrimary: Color? = null
            var resolvedOnPrimary: Color? = null

            setContent {
                CaraMLTheme(
                    preferences = ThemePreferences(
                        seedColor = seed,
                        themeMode = ThemeMode.DARK,
                        paletteStyle = ThemePaletteStyle.EXPRESSIVE,
                    ),
                ) {
                    val primary = MaterialTheme.colorScheme.primary
                    val onPrimary = MaterialTheme.colorScheme.onPrimary
                    SideEffect {
                        resolvedPrimary = primary
                        resolvedOnPrimary = onPrimary
                    }
                }
            }

            waitForIdle()

            assertEquals(seed, resolvedPrimary)
            assertTrue(
                contrastRatio(requireNotNull(resolvedPrimary), requireNotNull(resolvedOnPrimary)) >= 4.5f,
                "The exact seed accent must keep readable selected-control content",
            )
        }

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

private fun contrastRatio(first: Color, second: Color): Float {
    val lighter = maxOf(first.luminance(), second.luminance())
    val darker = minOf(first.luminance(), second.luminance())
    return (lighter + 0.05f) / (darker + 0.05f)
}
