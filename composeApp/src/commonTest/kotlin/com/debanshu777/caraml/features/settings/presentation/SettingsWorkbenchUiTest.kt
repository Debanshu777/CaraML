@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.features.settings.presentation

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.data.settings.SettingsRepository
import com.debanshu777.caraml.core.data.theme.ThemeRepository
import com.debanshu777.caraml.core.recommendation.RecommendationProfile
import com.debanshu777.caraml.core.recommendation.RecommendationRolloutMode
import com.debanshu777.caraml.core.recommendation.RecommendationRolloutModeSource
import com.debanshu777.caraml.core.settings.AppSettings
import com.debanshu777.caraml.core.theme.ThemePreferences
import com.debanshu777.caraml.core.theme.ThemeViewModel
import com.debanshu777.caraml.core.ui.motion.LocalAuroraMotionPolicy
import com.debanshu777.caraml.core.ui.motion.auroraMotionPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsWorkbenchUiTest {
    @Test
    fun settingsUsesSectionLabelsAndDividerRowsInsteadOfOutlinedPanePerGroup() =
        runComposeUiTest {
            val fixture = SettingsFixture()
            setContent {
                FixedDensity {
                    MaterialTheme(colorScheme = darkColorScheme()) {
                        Surface(
                            modifier = Modifier
                                .width(900.dp)
                                .height(1_000.dp)
                                .testTag("settings-test-host"),
                        ) {
                            fixture.Screen()
                        }
                    }
                }
            }

            listOf("Appearance", "Recommendations", "Generation", "Runtime").forEach { label ->
                onNodeWithText(label).performScrollTo().assertIsDisplayed()
            }

            val section = onNodeWithTag("settings-section-appearance")
                .fetchSemanticsNode().boundsInRoot
            val divider = onNodeWithTag("settings-divider-appearance")
                .fetchSemanticsNode().boundsInRoot
            assertTrue(abs(section.left - divider.left) <= 1f)
            assertTrue(abs(section.right - divider.right) <= 1f)
            assertTrue(divider.height <= 1.5f, "Settings divider was ${divider.height}px high")
        }

    @Test
    fun appearancePreviewIsTheOnlyContextualGradient() = runComposeUiTest {
        val fixture = SettingsFixture()
        setContent {
            FixedDensity {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Surface(Modifier.width(760.dp).height(900.dp)) {
                        fixture.Screen()
                    }
                }
            }
        }

        val previewNode = onNodeWithContentDescription("Current Aurora theme preview")
            .assertIsDisplayed()
        val pixels = previewNode.captureToImage().toPixelMap()
        val firstWash = pixels[pixels.width / 5, pixels.height / 4]
        val opposingWash = pixels[pixels.width * 4 / 5, pixels.height * 3 / 4]
        assertTrue(
            firstWash.rgbDistance(opposingWash) > 0.05f,
            "Appearance preview must visibly render the contextual gradient",
        )
        onAllNodes(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ContentDescription,
                listOf("Current Aurora theme preview"),
            ),
        ).assertCountEquals(1)
    }

    @Test
    fun paletteRecommendationKvGpuThemeAndSeedSelectionsHaveNonColorIndicators() =
        runComposeUiTest {
            val fixture = SettingsFixture()
            setContent {
                MaterialTheme {
                    Surface(Modifier.width(760.dp).height(1_000.dp)) {
                        fixture.Screen()
                    }
                }
            }

            onNodeWithContentDescription("Theme System, selected").performScrollTo()
            onNodeWithContentDescription(
                "Selected theme System",
                useUnmergedTree = true,
            ).assertIsDisplayed()
            onNodeWithContentDescription("Seed color 1").performScrollTo()
            onNodeWithContentDescription(
                "Selected seed color 1",
                useUnmergedTree = true,
            ).assertIsDisplayed()
            onNodeWithContentDescription("Palette Tonal Spot, selected").performScrollTo()
            onNodeWithContentDescription(
                "Selected palette Tonal Spot",
                useUnmergedTree = true,
            ).assertIsDisplayed()
            onNodeWithContentDescription("Risk tolerance Balanced, selected")
                .performScrollTo()
            onNodeWithContentDescription(
                "Selected risk tolerance Balanced",
                useUnmergedTree = true,
            ).assertIsDisplayed()
            onNodeWithContentDescription("Optimization priority Balanced, selected")
                .performScrollTo()
            onNodeWithContentDescription(
                "Selected optimization priority Balanced",
                useUnmergedTree = true,
            ).assertIsDisplayed()
            onNodeWithContentDescription("KV cache Auto, selected").performScrollTo()
            onNodeWithContentDescription(
                "Selected KV cache Auto",
                useUnmergedTree = true,
            ).assertIsDisplayed()
            onNodeWithContentDescription("GPU acceleration (Vulkan)")
                .performScrollTo()
                .assertIsOn()
        }

    @Test
    fun everyTouchedControlKeepsFortyEightDpTarget() = runComposeUiTest {
        val fixture = SettingsFixture()
        setContent {
            FixedDensity {
                MaterialTheme {
                    Surface(Modifier.width(760.dp).height(1_000.dp)) {
                        fixture.Screen()
                    }
                }
            }
        }

        listOf(
            "theme" to onNodeWithContentDescription("Theme System, selected"),
            "seed" to onNodeWithContentDescription("Seed color 1"),
            "palette" to onNodeWithContentDescription("Palette Tonal Spot, selected"),
            "recommendation" to
                onNodeWithContentDescription("Risk tolerance Balanced, selected"),
            "KV cache" to onNodeWithContentDescription("KV cache Auto, selected"),
            "temperature" to onNodeWithContentDescription("Temperature 0.3"),
            "disclosure" to onNodeWithText("Show details"),
            "GPU" to onNodeWithContentDescription("GPU acceleration (Vulkan)"),
            "calibration" to onNodeWithText("Run calibration"),
        ).forEach { (label, control) ->
            control.performScrollTo()
            val bounds = control.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.width >= 47.5f, "$label target width was ${bounds.width}dp")
            assertTrue(bounds.height >= 47.5f, "$label target height was ${bounds.height}dp")
        }
    }

    @Test
    fun descriptionExpansionSurvivesSaveableProviderRemoval() = runComposeUiTest {
        var mounted by mutableStateOf(true)
        setContent {
            val stateHolder = rememberSaveableStateHolder()
            MaterialTheme {
                if (mounted) {
                    stateHolder.SaveableStateProvider("kv-description") {
                        ExpandableSettingDescription(
                            summary = "Balanced memory and quality.",
                            details = "Saved disclosure details",
                        )
                    }
                }
            }
        }

        onNodeWithText("Show details").performClick()
        onNodeWithText("Saved disclosure details").assertIsDisplayed()

        runOnIdle { mounted = false }
        runOnIdle { mounted = true }

        onNodeWithText("Hide details").assertIsDisplayed()
        onNodeWithText("Saved disclosure details").assertIsDisplayed()
    }

    @Test
    fun reducedMotionDisclosureUsesStationaryFadeWithinNinetyMillis() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(
                LocalAuroraMotionPolicy provides auroraMotionPolicy(0f),
            ) {
                MaterialTheme {
                    ExpandableSettingDescription(
                        summary = "Balanced memory and quality.",
                        details = "Reduced motion details",
                        modifier = Modifier.testTag("reduced-motion-disclosure"),
                    )
                }
            }
        }

        onNodeWithText("Show details").performClick()
        mainClock.advanceTimeBy(45)
        val midBounds = onNodeWithText("Reduced motion details")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val midFade = onNodeWithText("Reduced motion details")
            .captureToImage().toPixelMap()

        mainClock.advanceTimeBy(50)
        val settledBounds = onNodeWithText("Reduced motion details")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val settled = onNodeWithText("Reduced motion details")
            .captureToImage().toPixelMap()

        assertEquals(midBounds.left, settledBounds.left, 0.5f)
        assertEquals(midBounds.top, settledBounds.top, 0.5f)
        assertTrue(
            midFade.differsFrom(settled),
            "Reduced motion disclosure must fade rather than appear immediately",
        )
    }

    @Test
    fun settingsUsesOneScrollOwnerAndCapsContentAtSevenHundredSixtyDp() =
        runComposeUiTest {
            val fixture = SettingsFixture()
            setContent {
                FixedDensity {
                    MaterialTheme {
                        Surface(Modifier.width(1_200.dp).height(800.dp)) {
                            fixture.Screen()
                        }
                    }
                }
            }

            onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
            ).assertCountEquals(1)
            val scrollBounds = onNodeWithTag("settings-scroll")
                .fetchSemanticsNode().boundsInRoot
            assertTrue(
                scrollBounds.width <= 760.5f,
                "Settings content width was ${scrollBounds.width}px",
            )
        }

    @Test
    fun settingsRemainReachableAtTwoHundredPercentTextInShortLandscape() =
        runComposeUiTest {
            val fixture = SettingsFixture()
            setContent {
                FixedDensity(fontScale = 2f) {
                    MaterialTheme {
                        Surface(Modifier.width(420.dp).height(280.dp)) {
                            fixture.Screen()
                        }
                    }
                }
            }

            onNodeWithContentDescription("GPU acceleration (Vulkan)")
                .performScrollTo()
                .assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
                .assertIsOn()
                .performClick()
                .assertIsOff()
        }
}

private class SettingsFixture {
    private val settingsRepository = FakeSettingsRepository()
    private val themeRepository = WorkbenchThemeRepository()
    private val settingsViewModel = SettingsViewModel(settingsRepository)
    private val themeViewModel = ThemeViewModel(themeRepository)
    private val rolloutModeSource = RecommendationRolloutModeSource {
        RecommendationRolloutMode.V2
    }

    @Composable
    fun Screen() {
        SettingsScreen(
            viewModel = settingsViewModel,
            themeViewModel = themeViewModel,
            rolloutModeSource = rolloutModeSource,
        )
    }
}

private class FakeSettingsRepository : SettingsRepository {
    private val settings = MutableStateFlow(
        AppSettings(modelProfileOnboardingComplete = true),
    )

    override fun getSettings(): Flow<AppSettings> = settings

    override suspend fun updateSettings(settings: AppSettings) {
        this.settings.value = settings
    }

    override suspend fun updateRecommendationProfile(profile: RecommendationProfile) {
        settings.value = settings.value.copy(
            riskTolerance = profile.riskTolerance,
            optimizationPriority = profile.optimizationPriority,
        )
    }

    override suspend fun completeModelProfileOnboarding(profile: RecommendationProfile) {
        settings.value = settings.value.copy(
            riskTolerance = profile.riskTolerance,
            optimizationPriority = profile.optimizationPriority,
            modelProfileOnboardingComplete = true,
        )
    }
}

private class WorkbenchThemeRepository : ThemeRepository {
    private val preferences = MutableStateFlow(ThemePreferences())

    override fun getPreferences(): Flow<ThemePreferences> = preferences

    override suspend fun updatePreferences(preferences: ThemePreferences) {
        this.preferences.value = preferences
    }
}

@Composable
private fun FixedDensity(
    fontScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalDensity provides Density(density = 1f, fontScale = fontScale),
        content = content,
    )
}

private fun Color.rgbDistance(other: Color): Float =
    abs(red - other.red) + abs(green - other.green) + abs(blue - other.blue)

private fun PixelMap.differsFrom(other: PixelMap): Boolean {
    val width = min(width, other.width)
    val height = min(height, other.height)
    for (y in 0 until height) {
        for (x in 0 until width) {
            if (this[x, y].rgbDistance(other[x, y]) > 0.015f) return true
        }
    }
    return false
}
