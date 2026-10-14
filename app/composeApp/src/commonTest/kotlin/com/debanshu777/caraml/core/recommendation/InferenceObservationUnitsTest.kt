package com.debanshu777.caraml.core.recommendation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InferenceObservationUnitsTest {
    @Test
    fun videoPerformanceCountsEveryDenoisingStepForEveryFrame() {
        assertEquals(100, diffusionObservationUnits(steps = 20, frames = 5))
        assertEquals(20, diffusionObservationUnits(steps = 20, frames = 1))
        assertNull(diffusionObservationUnits(steps = Int.MAX_VALUE, frames = 2))
    }
}
