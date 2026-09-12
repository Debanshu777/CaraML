package com.debanshu777.caraml.core.ui.motion

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
        assertEquals(100, policy.opacityDurationMillis)
    }

    @Test
    fun positiveDurationScaleUsesApprovedTimings() {
        val policy = auroraMotionPolicy(durationScale = 1f)

        assertTrue(policy.spatialTransitionsEnabled)
        assertTrue(policy.pulseEnabled)
        assertEquals(220, policy.peerTransitionMillis)
        assertEquals(300, policy.detailEnterMillis)
        assertEquals(200, policy.exitMillis)
        assertEquals(1600, policy.generationPulseMillis)
    }
}
