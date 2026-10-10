@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.core.ui.motion

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.core.theme.CaraMLTheme
import com.debanshu777.caraml.core.theme.ThemePreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertIs
import com.debanshu777.caraml.core.theme.AppMotion

class AuroraMotionPolicyTest {
    @Test
    fun reducedMotionSnapsSpatialSpecsAndKeepsBriefStatusFeedback() {
        val motion = AppMotion(auroraMotionPolicy(0f))

        assertIs<SnapSpec<Float>>(motion.drawerRevealSpec<Float>())
        assertIs<SnapSpec<Float>>(motion.pressSpec<Float>())
        assertIs<SnapSpec<Float>>(motion.pressInSpec<Float>())
        assertIs<SnapSpec<Float>>(motion.statusSpec<Float>())
        assertEquals(90, assertIs<TweenSpec<Float>>(motion.opacitySpec<Float>()).durationMillis)
    }

    @Test
    fun userReducedMotionOverridesSystemAndSoftEffectsReachTheTheme() = runComposeUiTest {
        lateinit var observed: AuroraMotionPolicy
        var softEffects = true
        setContent {
            CaraMLTheme(preferences = ThemePreferences(reduceMotion = true, softEffects = false)) {
                observed = LocalAuroraMotionPolicy.current
                softEffects = AppTheme.softEffects
                Text("Quiet theme")
            }
        }

        onNodeWithText("Quiet theme").assertIsDisplayed()
        runOnIdle {
            assertFalse(observed.spatialTransitionsEnabled)
            assertFalse(observed.pulseEnabled)
            assertFalse(softEffects)
        }
    }

    @Test
    fun zeroDurationScaleDisablesEverySpatialOrContinuousEffect() {
        val policy = auroraMotionPolicy(durationScale = 0f)

        assertFalse(policy.spatialTransitionsEnabled)
        assertFalse(policy.pulseEnabled)
        assertFalse(policy.shapeMorphEnabled)
        assertEquals(90, policy.opacityDurationMillis)
        assertEquals(90, policy.focalEntranceMillis)
    }

    @Test
    fun positiveDurationScaleUsesApprovedTimings() {
        val policy = auroraMotionPolicy(durationScale = 1f)

        assertTrue(policy.spatialTransitionsEnabled)
        assertTrue(policy.pulseEnabled)
        assertEquals(180, policy.opacityDurationMillis)
        assertEquals(180, policy.peerTransitionMillis)
        assertEquals(240, policy.detailEnterMillis)
        assertEquals(240, policy.hierarchicalPopEnterMillis)
        assertEquals(190, policy.exitMillis)
        assertEquals(1600, policy.generationPulseMillis)
        assertEquals(900, policy.focalEntranceMillis)
    }

    @Test
    fun zeroRuntimeDurationScaleReachesThePolicyProvidedByTheRealTheme() =
        runComposeUiTest(effectContext = FixedMotionDurationScale(0f)) {
            lateinit var observed: AuroraMotionPolicy
            setContent {
                CaraMLTheme(preferences = ThemePreferences()) {
                    observed = LocalAuroraMotionPolicy.current
                    Text("Theme content", color = AppTheme.colors.onSurface)
                }
            }

            onNodeWithText("Theme content").assertIsDisplayed()
            runOnIdle {
                assertEquals(0f, observed.durationScale)
                assertFalse(observed.spatialTransitionsEnabled)
            }
        }

    @Test
    fun nonzeroRuntimeDurationScaleIsReflectedByTheRealThemePolicy() =
        runComposeUiTest(effectContext = FixedMotionDurationScale(1.75f)) {
            lateinit var observed: AuroraMotionPolicy
            setContent {
                CaraMLTheme(preferences = ThemePreferences()) {
                    observed = LocalAuroraMotionPolicy.current
                    Text("Theme content", color = AppTheme.colors.onSurface)
                }
            }

            onNodeWithText("Theme content").assertIsDisplayed()
            runOnIdle {
                assertEquals(1.75f, observed.durationScale)
                assertTrue(observed.spatialTransitionsEnabled)
            }
        }
}

private class FixedMotionDurationScale(
    override val scaleFactor: Float,
) : MotionDurationScale
