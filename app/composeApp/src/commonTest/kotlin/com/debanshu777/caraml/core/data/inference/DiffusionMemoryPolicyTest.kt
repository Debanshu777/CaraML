package com.debanshu777.caraml.core.data.inference

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiffusionMemoryPolicyTest {
    @Test
    fun estimatesRawRgbaOutputWithOverflowProtection() {
        assertEquals(
            16_777_216L,
            estimateMediaOutputBytes(width = 512, height = 512, frames = 16),
        )
        assertEquals(
            Long.MAX_VALUE,
            estimateMediaOutputBytes(
                width = Int.MAX_VALUE,
                height = Int.MAX_VALUE,
                frames = Int.MAX_VALUE,
            ),
        )
    }

    @Test
    fun requiresWeightsRuntimeHeadroomAndTwoOutputCopies() {
        val oneGiB = 1_073_741_824L
        val sixtyFourMiB = 67_108_864L

        assertEquals(
            1_744_830_464L,
            requiredDiffusionMemoryBytes(
                weightsBytes = oneGiB,
                outputBytes = sixtyFourMiB,
            ),
        )
        assertEquals(
            671_088_640L,
            requiredDiffusionAdditionalMemoryBytes(
                loadedWeightsBytes = oneGiB,
                outputBytes = sixtyFourMiB,
            ),
        )
    }

    @Test
    fun acceptsOnlyRequestsWithinTheCurrentBudget() {
        assertTrue(
            fitsDiffusionMemoryBudget(
                weightsBytes = 1_073_741_824L,
                outputBytes = 67_108_864L,
                budgetBytes = 1_744_830_464L,
            )
        )
        assertFalse(
            fitsDiffusionMemoryBudget(
                weightsBytes = 1_073_741_824L,
                outputBytes = 67_108_864L,
                budgetBytes = 1_744_830_463L,
            )
        )
    }

    @Test
    fun combinesTheSaferMainWeightEstimateWithExternalComponents() {
        assertEquals(
            1_500L,
            estimateDiffusionWeightsBytes(
                mainFileBytes = 1_000L,
                nativeEstimatedBytes = 1_200L,
                componentBytes = 300L,
            ),
        )
        assertEquals(
            1_300L,
            estimateDiffusionWeightsBytes(
                mainFileBytes = 1_000L,
                nativeEstimatedBytes = 800L,
                componentBytes = 300L,
            ),
        )
    }
}
