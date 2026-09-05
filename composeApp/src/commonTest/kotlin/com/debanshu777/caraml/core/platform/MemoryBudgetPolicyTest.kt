package com.debanshu777.caraml.core.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class MemoryBudgetPolicyTest {
    @Test
    fun choosesTheSmallerTotalOrCurrentlyAvailableCeiling() {
        assertEquals(
            700L,
            conservativeMemoryBudget(totalBytes = 1_000L, currentlyAvailableBytes = 2_000L),
        )
        assertEquals(
            300L,
            conservativeMemoryBudget(totalBytes = 1_000L, currentlyAvailableBytes = 400L),
        )
    }

    @Test
    fun handlesMissingAndInvalidReadingsConservatively() {
        assertEquals(
            700L,
            conservativeMemoryBudget(totalBytes = 1_000L, currentlyAvailableBytes = null),
        )
        assertEquals(
            300L,
            conservativeMemoryBudget(totalBytes = 0L, currentlyAvailableBytes = 400L),
        )
        assertEquals(
            0L,
            conservativeMemoryBudget(totalBytes = -1L, currentlyAvailableBytes = -1L),
        )
    }

    @Test
    fun percentageArithmeticCannotOverflow() {
        assertEquals(
            6_456_360_425_798_343_064L,
            conservativeMemoryBudget(totalBytes = Long.MAX_VALUE, currentlyAvailableBytes = null),
        )
    }
}
