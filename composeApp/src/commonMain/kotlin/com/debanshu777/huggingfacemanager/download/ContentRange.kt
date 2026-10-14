package com.debanshu777.huggingfacemanager.download

data class ContentRange(
    val start: Long,
    val endInclusive: Long,
    val total: Long,
) {
    companion object {
        fun parse(value: String?): ContentRange? {
            if (value == null || !value.startsWith("bytes ")) return null
            val rangeAndTotal = value.removePrefix("bytes ").split('/')
            if (rangeAndTotal.size != 2) return null
            val bounds = rangeAndTotal[0].split('-')
            if (bounds.size != 2) return null
            val start = bounds[0].toLongOrNull() ?: return null
            val end = bounds[1].toLongOrNull() ?: return null
            val total = rangeAndTotal[1].toLongOrNull() ?: return null
            if (start < 0L || end < start || total <= 0L || end >= total) return null
            return ContentRange(start, end, total)
        }
    }
}
