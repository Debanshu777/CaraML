package com.debanshu777.caraml.core.rating

private val SIZE_HINT_REGEX = Regex("""(\d+(?:\.\d+)?)\s*(GB|MB|KB|B)""", RegexOption.IGNORE_CASE)

/**
 * Parses a human-readable size string (e.g. "4.2 GB", "256 MB") to bytes.
 * Returns null when the string cannot be parsed.
 */
fun parseSizeHintToBytes(sizeHint: String): Long? {
    return try {
        val match = SIZE_HINT_REGEX.find(sizeHint) ?: return null
        val (valueStr, unit) = match.destructured
        val value = valueStr.toDouble()
        when (unit.uppercase()) {
            "GB" -> (value * 1024 * 1024 * 1024).toLong()
            "MB" -> (value * 1024 * 1024).toLong()
            "KB" -> (value * 1024).toLong()
            "B" -> value.toLong()
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}
