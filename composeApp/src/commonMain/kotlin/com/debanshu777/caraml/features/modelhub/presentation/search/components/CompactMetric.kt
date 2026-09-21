package com.debanshu777.caraml.features.modelhub.presentation.search.components

/** Keeps high-volume registry metrics scannable without locale-specific formatting APIs. */
internal fun formatCompactMetric(value: Long): String = when {
    value < 0L -> value.toString()
    value >= 1_000_000_000L -> formatCompactMetric(value, 1_000_000_000L, "B")
    value >= 1_000_000L -> formatCompactMetric(value, 1_000_000L, "M")
    value >= 1_000L -> formatCompactMetric(value, 1_000L, "K")
    else -> value.toString()
}

private fun formatCompactMetric(value: Long, divisor: Long, suffix: String): String {
    val whole = value / divisor
    val remainder = value % divisor
    val roundedTenths = whole * 10L + ((remainder * 10L + divisor / 2L) / divisor)
    return if (roundedTenths % 10L == 0L) {
        "${roundedTenths / 10L}$suffix"
    } else {
        "${roundedTenths / 10L}.${roundedTenths % 10L}$suffix"
    }
}
