package com.debanshu777.caraml.core.platform

import com.debanshu777.caraml.core.recommendation.CheckedLong
import com.debanshu777.caraml.core.recommendation.checkedPercentage

internal const val MIB_BYTES = 1_048_576L
internal const val GIB_BYTES = 1_073_741_824L
private const val MIN_STORAGE_RESERVE_BYTES = 512L * MIB_BYTES
private const val MAX_STORAGE_RESERVE_BYTES = 10L * GIB_BYTES

internal fun computeBaseBudget(
    allocatable: Long,
    osThreshold: Long,
    noiseP95: Long,
    minimum: Long,
): Long? {
    if (allocatable < 0L || osThreshold < 0L || noiseP95 < 0L || minimum < 0L) return null
    val reserve = maxOf(osThreshold, noiseP95, minimum)
    return if (reserve >= allocatable) 0L else allocatable - reserve
}

internal fun computeStorageReserve(freeStorageBytes: Long): Long? {
    if (freeStorageBytes < 0L) return null
    val fivePercent = when (val value = checkedPercentage(freeStorageBytes, 5)) {
        is CheckedLong.Value -> value.value
        is CheckedLong.Invalid -> return null
    }
    return minOf(MAX_STORAGE_RESERVE_BYTES, maxOf(MIN_STORAGE_RESERVE_BYTES, fivePercent))
}

internal fun computeStorageBudget(freeStorageBytes: Long): Long? {
    val reserve = computeStorageReserve(freeStorageBytes) ?: return null
    return if (reserve >= freeStorageBytes) 0L else freeStorageBytes - reserve
}

internal fun conservativeMemoryBudget(
    totalBytes: Long,
    currentlyAvailableBytes: Long?,
): Long {
    val totalCeiling = totalBytes.takeIf { it > 0L }?.let { percentageOf(it, 70L) }
    val availableCeiling = currentlyAvailableBytes
        ?.takeIf { it > 0L }
        ?.let { percentageOf(it, 75L) }
    return when {
        totalCeiling != null && availableCeiling != null -> minOf(totalCeiling, availableCeiling)
        totalCeiling != null -> totalCeiling
        availableCeiling != null -> availableCeiling
        else -> 0L
    }
}

private fun percentageOf(value: Long, percentage: Long): Long =
    (value / 100L) * percentage + ((value % 100L) * percentage) / 100L
