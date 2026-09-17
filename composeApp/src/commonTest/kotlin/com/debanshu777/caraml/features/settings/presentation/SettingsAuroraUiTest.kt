@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.features.settings.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.data.theme.ThemeRepository
import com.debanshu777.caraml.core.recommendation.RecommendationProfile
import com.debanshu777.caraml.core.recommendation.RiskTolerance
import com.debanshu777.caraml.core.settings.KvQuantPreset
import com.debanshu777.caraml.core.theme.ThemeDefaults
import com.debanshu777.caraml.core.theme.ThemePaletteStyle
import com.debanshu777.caraml.core.theme.ThemePreferences
import com.debanshu777.caraml.core.theme.ThemeViewModel
import com.debanshu777.caraml.core.ui.motion.LocalAuroraMotionPolicy
import com.debanshu777.caraml.core.ui.motion.auroraMotionPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsAuroraUiTest {
    @Test
    fun appearancePreviewIsIdentifiableWithoutDependingOnColor() = runComposeUiTest {
        setContent { MaterialTheme { AuroraThemePreview() } }

        onNodeWithContentDescription("Current Aurora theme preview").assertIsDisplayed()
    }

    @Test
    fun longDescriptionExpandsAndCollapsesAccessibly() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ExpandableSettingDescription(
                    summary = "Balanced memory and quality.",
                    details = "Uses Q8 keys and values for most devices.",
                )
            }
        }

        onNodeWithText("Show details")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        onNodeWithText("Uses Q8 keys and values for most devices.").assertIsDisplayed()
        onNodeWithText("Hide details").performClick()
        onNodeWithText("Uses Q8 keys and values for most devices.").assertDoesNotExist()
    }

    @Test
    fun reducedMotionDisclosureReachesFinalStateWithinOpacityBudget() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(
                LocalAuroraMotionPolicy provides auroraMotionPolicy(0f),
            ) {
                MaterialTheme {
                    ExpandableSettingDescription(
                        summary = "Balanced memory and quality.",
                        details = "Details",
                    )
                }
            }
        }

        onNodeWithText("Show details").performClick()
        mainClock.advanceTimeBy(100)
        onNodeWithText("Details").assertIsDisplayed()
    }

    @Test
    fun disclosureSummaryAndActionRemainReachableAtTwoHundredPercentFontScale() =
        runComposeUiTest {
            setContent {
                AtTwoHundredPercentFontScale {
                    MaterialTheme {
                        Box(Modifier.width(360.dp).height(180.dp)) {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                Spacer(Modifier.height(240.dp))
                                ExpandableSettingDescription(
                                    summary = "Balanced memory and quality.",
                                    details = "Uses Q8 keys and values for most devices.",
                                )
                            }
                        }
                    }
                }
            }

            onNodeWithText("Show details")
                .performScrollTo()
                .assertIsDisplayed()
            onNodeWithText("Balanced memory and quality.").assertIsDisplayed()
        }

    @Test
    fun paletteSelectionShowsAVisibleIndicatorAndPreservesTheSelectedValue() = runComposeUiTest {
        val repository = FakeThemeRepository()
        val viewModel = ThemeViewModel(repository)
        setContent {
            MaterialTheme {
                AppearanceSection(viewModel = viewModel)
            }
        }

        onNodeWithContentDescription("Palette Vibrant, not selected")
            .assertIsNotSelected()
            .assertHeightIsAtLeast(48.dp)
        onNodeWithContentDescription(
            "Selected palette Vibrant",
            useUnmergedTree = true,
        ).assertDoesNotExist()

        onNodeWithContentDescription("Palette Vibrant, not selected").performClick()

        onNodeWithContentDescription("Palette Vibrant, selected").assertIsSelected()
        onNodeWithContentDescription(
            "Selected palette Vibrant",
            useUnmergedTree = true,
        ).assertIsDisplayed()
        runOnIdle {
            assertEquals(ThemePaletteStyle.VIBRANT, repository.preferences.value.paletteStyle)
        }
    }

    @Test
    fun recommendationSelectionShowsAVisibleIndicatorAndPreservesTheCallbackValue() =
        runComposeUiTest {
            var profile by mutableStateOf(RecommendationProfile())
            var selectedRisk: RiskTolerance? = null
            setContent {
                MaterialTheme {
                    RecommendationProfileSection(
                        profile = profile,
                        onRiskToleranceChange = {
                            selectedRisk = it
                            profile = profile.copy(riskTolerance = it)
                        },
                        onOptimizationPriorityChange = {},
                    )
                }
            }

            onNodeWithText("Experimental")
                .assertIsNotSelected()
                .assertHeightIsAtLeast(48.dp)
            onNodeWithContentDescription(
                "Selected risk tolerance Experimental",
                useUnmergedTree = true,
            ).assertDoesNotExist()

            onNodeWithText("Experimental").performClick()

            onNodeWithText("Experimental").assertIsSelected()
            onNodeWithContentDescription(
                "Selected risk tolerance Experimental",
                useUnmergedTree = true,
            ).assertIsDisplayed()
            runOnIdle { assertEquals(RiskTolerance.EXPERIMENTAL, selectedRisk) }
        }

    @Test
    fun kvSelectionShowsAVisibleIndicatorAndPreservesTheUpdatedPreset() = runComposeUiTest {
        var selected by mutableStateOf(KvQuantPreset.AUTO)
        var callbackValue: KvQuantPreset? = null
        setContent {
            MaterialTheme {
                KvCacheSection(
                    selected = selected,
                    onSelect = {
                        callbackValue = it
                        selected = it
                    },
                )
            }
        }

        onNodeWithText("Q8/Q8")
            .assertIsNotSelected()
            .assertHeightIsAtLeast(48.dp)
        onNodeWithContentDescription(
            "Selected KV cache Q8/Q8",
            useUnmergedTree = true,
        ).assertDoesNotExist()

        onNodeWithText("Q8/Q8").performClick()

        onNodeWithText("Q8/Q8").assertIsSelected()
        onNodeWithContentDescription(
            "Selected KV cache Q8/Q8",
            useUnmergedTree = true,
        ).assertIsDisplayed()
        runOnIdle { assertEquals(KvQuantPreset.Q8_Q8, callbackValue) }
    }

    @Test
    fun gpuRowIsTheSingleLabeledToggleAndPreservesTheUpdatedValue() = runComposeUiTest {
        var enabled by mutableStateOf(true)
        var callbackValue: Boolean? = null
        setContent {
            MaterialTheme {
                GpuAccelerationSection(
                    enabled = enabled,
                    onToggle = {
                        callbackValue = it
                        enabled = it
                    },
                )
            }
        }

        val gpuToggle = onNodeWithContentDescription("GPU acceleration (Vulkan)")
        gpuToggle
            .assertIsOn()
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
        onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState),
        ).assertCountEquals(1)

        gpuToggle.performClick()

        gpuToggle.assertIsOff()
        runOnIdle { assertEquals(false, callbackValue) }
    }

    @Test
    fun everySeedUsesASeparateSelectedBadgeInLightAndDarkThemes() = runComposeUiTest {
        val repository = FakeThemeRepository()
        val viewModel = ThemeViewModel(repository)
        var darkTheme by mutableStateOf(false)
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 0.5f)) {
                MaterialTheme(
                    colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme(),
                ) {
                    AppearanceSection(viewModel = viewModel)
                }
            }
        }

        listOf(false, true).forEach { useDarkTheme ->
            runOnIdle { darkTheme = useDarkTheme }
            mainClock.advanceTimeByFrame()
            ThemeDefaults.PRESET_SEEDS.forEachIndexed { index, seed ->
                runOnIdle {
                    repository.preferences.value = repository.preferences.value.copy(
                        seedColor = seed,
                    )
                }
                mainClock.advanceTimeByFrame()

                onNodeWithContentDescription("Seed color ${index + 1}")
                    .assertIsSelected()
                onNodeWithContentDescription(
                    "Selected seed color ${index + 1}",
                    useUnmergedTree = true,
                ).assertIsDisplayed()
            }
        }
    }
}

private class FakeThemeRepository : ThemeRepository {
    val preferences = MutableStateFlow(ThemePreferences())

    override fun getPreferences(): Flow<ThemePreferences> = preferences

    override suspend fun updatePreferences(preferences: ThemePreferences) {
        this.preferences.value = preferences
    }
}

@Composable
private fun AtTwoHundredPercentFontScale(content: @Composable () -> Unit) {
    val current = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(current.density, fontScale = 2f),
        content = content,
    )
}
