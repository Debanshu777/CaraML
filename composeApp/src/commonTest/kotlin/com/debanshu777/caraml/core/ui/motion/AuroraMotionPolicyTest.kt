@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.core.ui.motion

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.debanshu777.caraml.core.theme.CaraMLTheme
import com.debanshu777.caraml.core.theme.ThemePreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuroraMotionPolicyTest {
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
                    Text("Theme content", color = MaterialTheme.colorScheme.onSurface)
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
                    Text("Theme content", color = MaterialTheme.colorScheme.onSurface)
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
