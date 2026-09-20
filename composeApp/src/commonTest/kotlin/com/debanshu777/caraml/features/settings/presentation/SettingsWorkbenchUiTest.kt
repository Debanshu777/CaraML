@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.features.settings.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
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
    fun appearancePreviewUsesSharedGradientAndGrainTreatment() = runComposeUiTest {
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
        assertTrue(
            pixels.highFrequencyEnergy(bottom = pixels.height / 2) >= 0.0025f,
            "Appearance preview must render the shared deterministic grain",
        )
        onAllNodes(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ContentDescription,
                listOf("Current Aurora theme preview"),
            ),
        ).assertCountEquals(1)
    }

    @Test
    fun appearanceChoiceTitlesRemainReadableWithoutAnOpaqueParentSurface() =
        runComposeUiTest {
            val background = Color(0xFF0C100C)
            val foreground = Color(0xFFF4F5ED)
            val viewModel = ThemeViewModel(WorkbenchThemeRepository())
            setContent {
                FixedDensity {
                    MaterialTheme(
                        colorScheme = darkColorScheme(
                            surface = background,
                            onSurface = foreground,
                            primary = Color(0xFFF0D048),
                            secondary = Color(0xFF72CFA3),
                            tertiary = Color(0xFFC9A5FF),
                        ),
                    ) {
                        Box(
                            modifier = Modifier
                                .width(760.dp)
                                .height(1_000.dp)
                                .background(background),
                        ) {
                            AppearanceSection(viewModel = viewModel)
                        }
                    }
                }
            }

            listOf("Theme", "Seed color", "Palette style").forEach { title ->
                val contrast = onNodeWithText(title)
                    .assertIsDisplayed()
                    .captureToImage()
                    .toPixelMap()
                    .internalContrast()
                assertTrue(
                    contrast >= 4.5f,
                    "$title must not inherit an unreadable root content color; contrast was $contrast",
                )
            }
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
            onNodeWithTag(
                "Selected theme System",
                useUnmergedTree = true,
            ).assertIsDisplayed()
            onNodeWithContentDescription(
                "Selected theme System",
                useUnmergedTree = true,
            ).assertDoesNotExist()
            onNodeWithContentDescription("Seed color 1").performScrollTo()
            onNodeWithTag(
                "Selected seed color 1",
                useUnmergedTree = true,
            ).assertIsDisplayed()
            onNodeWithContentDescription(
                "Selected seed color 1",
                useUnmergedTree = true,
            ).assertDoesNotExist()
            onNodeWithContentDescription("Palette Tonal Spot, selected").performScrollTo()
            onNodeWithTag(
                "Selected palette Tonal Spot",
                useUnmergedTree = true,
            ).assertIsDisplayed()
            onNodeWithContentDescription("Risk tolerance Balanced, selected")
                .performScrollTo()
            onNodeWithTag(
                "Selected risk tolerance Balanced",
                useUnmergedTree = true,
            ).assertIsDisplayed()
            onNodeWithContentDescription("Optimization priority Balanced, selected")
                .performScrollTo()
            onNodeWithTag(
                "Selected optimization priority Balanced",
                useUnmergedTree = true,
            ).assertIsDisplayed()
            onNodeWithContentDescription("KV cache Auto, selected").performScrollTo()
            onNodeWithTag(
                "Selected KV cache Auto",
                useUnmergedTree = true,
            ).assertIsDisplayed()
            onNodeWithContentDescription("GPU acceleration (Vulkan)")
                .performScrollTo()
                .assertIsOn()
        }

    @Test
    fun exclusiveSettingsChoicesExposeOneRadioActionAndRemainExclusive() =
        runComposeUiTest {
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

            onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup),
                useUnmergedTree = true,
            ).assertCountEquals(6)

            listOf(
                "Theme System, selected" to true,
                "Theme Light, not selected" to false,
                "Seed color 1" to true,
                "Seed color 2" to false,
                "Palette Tonal Spot, selected" to true,
                "Palette Vibrant, not selected" to false,
                "Risk tolerance Balanced, selected" to true,
                "Risk tolerance Experimental, not selected" to false,
                "Optimization priority Balanced, selected" to true,
                "Optimization priority Quality & context, not selected" to false,
                "KV cache Auto, selected" to true,
                "KV cache Q8/Q8, not selected" to false,
            ).forEach { (description, selected) ->
                val node = onNodeWithContentDescription(
                    description,
                    useUnmergedTree = true,
                ).performScrollTo().fetchSemanticsNode()
                assertEquals(Role.RadioButton, node.config[SemanticsProperties.Role])
                assertEquals(selected, node.config[SemanticsProperties.Selected])
                val optionBounds = node.boundsInRoot
                val actionCount = onAllNodes(
                    SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes().count { actionNode ->
                    val center = actionNode.boundsInRoot.center
                    center.x >= optionBounds.left && center.x <= optionBounds.right &&
                        center.y >= optionBounds.top && center.y <= optionBounds.bottom
                }
                assertEquals(
                    1,
                    actionCount,
                    "$description must expose exactly one click action",
                )
            }

            listOf(
                "Theme Light" to listOf("Theme System", "Theme Light", "Theme Dark"),
                "Palette Vibrant" to listOf(
                    "Palette Tonal Spot",
                    "Palette Neutral",
                    "Palette Vibrant",
                    "Palette Expressive",
                    "Palette Content",
                    "Palette Fidelity",
                    "Palette Monochrome",
                    "Palette Rainbow",
                    "Palette Fruit Salad",
                ),
                "Risk tolerance Experimental" to listOf(
                    "Risk tolerance Conservative",
                    "Risk tolerance Balanced",
                    "Risk tolerance Experimental",
                ),
                "Optimization priority Quality & context" to listOf(
                    "Optimization priority Speed & efficiency",
                    "Optimization priority Balanced",
                    "Optimization priority Quality & context",
                ),
                "KV cache Q8/Q8" to listOf(
                    "KV cache Auto",
                    "KV cache Q4/F16",
                    "KV cache Q8/Q8",
                    "KV cache F16/F16",
                ),
            ).forEach { (nextSelection, group) ->
                onNodeWithContentDescription("$nextSelection, not selected")
                    .performScrollTo()
                    .performClick()
                group.forEach { option ->
                    if (option == nextSelection) {
                        onNodeWithContentDescription("$option, selected").assertIsSelected()
                    } else {
                        onNodeWithContentDescription("$option, not selected").assertIsNotSelected()
                    }
                }
            }

            onNodeWithContentDescription("Seed color 2")
                .performScrollTo()
                .performClick()
                .assertIsSelected()
            onNodeWithContentDescription("Seed color 1").assertIsNotSelected()
            onAllNodes(
                SemanticsMatcher.expectValue(SemanticsProperties.Selected, true) and
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.Role,
                        Role.RadioButton,
                    ),
            ).assertCountEquals(6)
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

            val preview = onNodeWithContentDescription("Current Aurora theme preview")
                .performScrollTo()
                .assertIsDisplayed()
                .fetchSemanticsNode()
            val previewLeft = preview.positionInRoot.x
            val previewTop = preview.positionInRoot.y
            val previewRight = previewLeft + preview.size.width
            val previewBottom = previewTop + preview.size.height
            val previewContentInset = 15.5f
            assertTrue(
                preview.size.height >= 113.5f,
                "200% text preview must grow beyond the 104dp normal-density minimum",
            )

            onAllNodesWithText("Prism workbench").assertCountEquals(0)
            listOf("CaraML workspace", "Seed color, atmosphere, and grain").forEach { label ->
                val labelNode = onNodeWithText(label, useUnmergedTree = true)
                    .fetchSemanticsNode()
                val labelLeft = labelNode.positionInRoot.x
                val labelTop = labelNode.positionInRoot.y
                val labelRight = labelLeft + labelNode.size.width
                val labelBottom = labelTop + labelNode.size.height
                assertTrue(
                    labelLeft >= previewLeft + previewContentInset &&
                        labelTop >= previewTop + previewContentInset &&
                        labelRight <= previewRight - previewContentInset &&
                        labelBottom <= previewBottom - previewContentInset,
                    "$label bounds must stay inside the 200% text preview safe inset",
                )
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

private fun PixelMap.highFrequencyEnergy(
    top: Int = 1,
    bottom: Int = height - 1,
): Float {
    var total = 0f
    var count = 0
    for (y in top.coerceAtLeast(1) until bottom.coerceAtMost(height - 1)) {
        for (x in 1 until width - 1) {
            val center = this[x, y].signal()
            total += abs((2f * center) - this[x - 1, y].signal() - this[x + 1, y].signal())
            total += abs((2f * center) - this[x, y - 1].signal() - this[x, y + 1].signal())
            count += 1
        }
    }
    return total / count
}

private fun PixelMap.internalContrast(): Float {
    var minimum = 1f
    var maximum = 0f
    for (y in 0 until height) {
        for (x in 0 until width) {
            val luminance = this[x, y].luminance()
            minimum = min(minimum, luminance)
            maximum = maxOf(maximum, luminance)
        }
    }
    return (maximum + 0.05f) / (minimum + 0.05f)
}

private fun Color.signal(): Float = (red + green + blue) / 3f
