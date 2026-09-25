package com.debanshu777.caraml.core.recommendation

sealed interface CheckedLong {
    data class Value(val value: Long) : CheckedLong

    data class Invalid(val reason: AssessmentReason) : CheckedLong
}

internal fun checkedAdd(left: Long, right: Long): CheckedLong = when {
    left < 0L || right < 0L -> CheckedLong.Invalid(AssessmentReason.INVALID_METADATA)
    left > Long.MAX_VALUE - right -> CheckedLong.Invalid(AssessmentReason.ARITHMETIC_OVERFLOW)
    else -> CheckedLong.Value(left + right)
}

internal fun checkedSubtractNonNegative(left: Long, right: Long): CheckedLong = when {
    left < 0L || right < 0L || right > left -> CheckedLong.Invalid(AssessmentReason.INVALID_METADATA)
    else -> CheckedLong.Value(left - right)
}

internal fun checkedMultiply(left: Long, right: Long): CheckedLong = when {
    left < 0L || right < 0L -> CheckedLong.Invalid(AssessmentReason.INVALID_METADATA)
    left != 0L && right > Long.MAX_VALUE / left ->
        CheckedLong.Invalid(AssessmentReason.ARITHMETIC_OVERFLOW)
    else -> CheckedLong.Value(left * right)
}

internal fun checkedPercentage(value: Long, percent: Int): CheckedLong = when {
    value < 0L || percent !in 0..100 -> CheckedLong.Invalid(AssessmentReason.INVALID_METADATA)
    else -> checkedAdd((value / 100L) * percent, ((value % 100L) * percent) / 100L)
}

@ConsistentCopyVisibility
data class EstimateRange private constructor(
    val lowBytes: Long,
    val likelyBytes: Long,
    val highBytes: Long,
) {
    companion object {
        fun create(lowBytes: Long, likelyBytes: Long, highBytes: Long): CheckedEstimateRange =
            if (lowBytes < 0L || lowBytes > likelyBytes || likelyBytes > highBytes) {
                CheckedEstimateRange.Invalid(AssessmentReason.INVALID_ESTIMATE_RANGE)
            } else {
                CheckedEstimateRange.Value(EstimateRange(lowBytes, likelyBytes, highBytes))
            }
    }
}

sealed interface CheckedEstimateRange {
    data class Value(val range: EstimateRange) : CheckedEstimateRange

    data class Invalid(val reason: AssessmentReason) : CheckedEstimateRange
}
