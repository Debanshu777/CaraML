package com.debanshu777.caraml.features.chat.data

class TokenTimer {
    private var firstTokenTimeMs: Long? = null
    private var lastTokenTimeMs: Long? = null
    private var count = 0

    fun onToken() {
        val now = currentTimeMillis()
        if (firstTokenTimeMs == null) {
            firstTokenTimeMs = now
        }
        lastTokenTimeMs = now
        count++
    }

    fun buildMetrics(): InferenceMetrics? {
        val first = firstTokenTimeMs ?: return null
        val last = lastTokenTimeMs ?: return null
        val elapsed = last - first
        val tpot = if (count > 1) elapsed.toDouble() / (count - 1) else 0.0
        return InferenceMetrics(
            tpotMs = tpot,
            tokenCount = count,
            generationTimeMs = elapsed,
        )
    }

    fun buildLiveMetrics(): Pair<Int, Double> {
        val first = firstTokenTimeMs ?: return Pair(0, 0.0)
        val last = lastTokenTimeMs ?: return Pair(0, 0.0)
        val elapsed = last - first
        val tokensPerSecond = if (count > 1 && elapsed > 0) {
            (count - 1) * 1000.0 / elapsed
        } else {
            0.0
        }
        return Pair(count, tokensPerSecond)
    }
}

expect fun currentTimeMillis(): Long
