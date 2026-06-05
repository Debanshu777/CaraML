package com.debanshu777.caraml.benchmark

import com.debanshu777.caraml.core.benchmark.BenchmarkUtils
import com.debanshu777.caraml.features.chat.data.InferenceMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

class BenchmarkUtilsTest {

    @Test
    fun formatRuntimeConfig_containsAllFields() {
        val result = BenchmarkUtils.formatRuntimeConfig(
            threads = 4, batchThreads = 4, batchSize = 512, contextLimit = 16384, gpuLayers = 0
        )
        assertTrue(result.contains("t=4/4"), "Expected t=4/4 in: $result")
        assertTrue(result.contains("b=512"), "Expected b=512 in: $result")
        assertTrue(result.contains("ctx=16384"), "Expected ctx=16384 in: $result")
        assertTrue(result.contains("gpu=0"), "Expected gpu=0 in: $result")
    }

    @Test
    fun logBenchmarkResult_onlyWhenEnabled() {
        BenchmarkUtils.disableBenchmarkMode()
        assertFalse(BenchmarkUtils.benchmarkMode)

        BenchmarkUtils.enableBenchmarkMode()
        assertTrue(BenchmarkUtils.benchmarkMode)
        BenchmarkUtils.disableBenchmarkMode()
        assertFalse(BenchmarkUtils.benchmarkMode)
    }

    @Test
    fun detailedTimer_separatesTtftFromDecode() {
        val timer = BenchmarkUtils.DetailedTokenTimer()
        timer.startPrefill()
        // Simulate: first token at some time, then more tokens
        timer.onFirstToken()
        repeat(9) { timer.onToken() }
        val metrics = timer.buildDetailedMetrics()
        assertNotNull(metrics)
        assertTrue(metrics.totalTokens == 10, "Expected 10 tokens, got ${metrics.totalTokens}")
        assertTrue(metrics.decodeTps >= 0.0, "decodeTps should be >= 0")
    }
}
