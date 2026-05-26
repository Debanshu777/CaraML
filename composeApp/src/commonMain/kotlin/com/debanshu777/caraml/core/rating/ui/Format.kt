package com.debanshu777.caraml.core.rating.ui

/**
 * Multiplatform byte formatter used across rating UI surfaces.
 * Mirrors the previously-duplicated helpers in VariantPickerRow.kt and
 * InstallBundleCard.kt — those should call this instead of re-implementing.
 */
fun formatBytesHuman(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    val display = if (value >= 100 || unitIndex == 0) {
        value.toInt().toString()
    } else {
        val rounded = kotlin.math.round(value * 10.0) / 10.0
        if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
    }
    return "$display ${units[unitIndex]}"
}
