package com.debanshu777.caraml.core.recommendation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CheckedResourceMathTest {
    @Test
    fun checkedArithmeticRejectsOverflowAndInvalidRanges() {
        assertIs<CheckedLong.Invalid>(checkedAdd(Long.MAX_VALUE, 1L))
        assertIs<CheckedLong.Invalid>(checkedSubtractNonNegative(1L, 2L))
        assertIs<CheckedLong.Invalid>(checkedMultiply(Long.MAX_VALUE, 2L))
        assertIs<CheckedEstimateRange.Invalid>(EstimateRange.create(10L, 9L, 11L))
        assertEquals(CheckedLong.Value(15L), checkedAdd(7L, 8L))
        assertEquals(CheckedLong.Value(850L), checkedPercentage(1_000L, 85))
    }

    @Test
    fun checkedArithmeticRejectsInvalidExternalValuesBeforeCalculating() {
        assertEquals(
            CheckedLong.Invalid(AssessmentReason.INVALID_METADATA),
            checkedAdd(-1L, 1L),
        )
        assertEquals(
            CheckedLong.Invalid(AssessmentReason.INVALID_METADATA),
            checkedMultiply(1L, -1L),
        )
        assertEquals(
            CheckedLong.Invalid(AssessmentReason.INVALID_METADATA),
            checkedPercentage(100L, 101),
        )
        assertEquals(
            CheckedEstimateRange.Invalid(AssessmentReason.INVALID_ESTIMATE_RANGE),
            EstimateRange.create(-1L, 0L, 1L),
        )
    }

    @Test
    fun checkedPercentagePreservesLargeValidValuesWithoutOverflow() {
        assertEquals(CheckedLong.Value(Long.MAX_VALUE), checkedPercentage(Long.MAX_VALUE, 100))
    }
}
