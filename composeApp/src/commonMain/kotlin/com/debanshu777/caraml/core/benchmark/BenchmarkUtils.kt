package com.debanshu777.caraml.core.benchmark

import com.debanshu777.caraml.core.platform.AppLogger
import com.debanshu777.caraml.features.chat.data.InferenceMetrics
import com.debanshu777.caraml.features.chat.data.currentTimeMillis
import kotlinx.coroutines.delay
import kotlin.concurrent.Volatile

/**
 * Utilities for standardized inference performance benchmarking.
 * 
 * Addresses thermal throttling, measurement consistency, and result interpretation
 * as documented in docs/benchmark-protocol.md
 */
object BenchmarkUtils {
    private const val TAG = "BENCHMARK"
    
    @Volatile var benchmarkMode = false
        private set
    @Volatile private var currentRunId = 0
    
    /**
     * Enable benchmark logging mode. When enabled, detailed performance metrics
     * are logged with BENCHMARK tag for analysis.
     */
    fun enableBenchmarkMode() {
        benchmarkMode = true
        currentRunId = 0
        AppLogger.i(TAG) { "Benchmark mode enabled" }
    }
    
    /**
     * Disable benchmark logging mode.
     */
    fun disableBenchmarkMode() {
        benchmarkMode = false
        AppLogger.i(TAG) { "Benchmark mode disabled" }
    }
    
    /**
     * Log a benchmark result with standardized format.
     * Only logs if benchmark mode is enabled.
     */
    fun logBenchmarkResult(
        metrics: InferenceMetrics?,
        contextUsage: String,
        runtimeConfig: String,
        deviceTemp: Float? = null,
        notes: String = ""
    ) {
        if (!benchmarkMode) return
        
        currentRunId++
        val tps = metrics?.tokensPerSecond?.let { (it * 100).toInt() / 100.0 } ?: 0.0
        val tempStr = deviceTemp?.let { "${it}°C" } ?: "N/A"
        
        AppLogger.i(TAG) {
            "RUN_$currentRunId: tps=$tps, " +
            "tokens=${metrics?.tokenCount ?: 0}, " +
            "time=${metrics?.generationTimeMs ?: 0}ms, " +
            "context=$contextUsage, " +
            "temp=$tempStr, " +
            "config=[$runtimeConfig]" +
            if (notes.isNotEmpty()) ", notes=[$notes]" else ""
        }
    }
    
    /**
     * Enhanced timer that separates TTFT (Time To First Token) from decode TPS.
     * Addresses the issue where prefill time skews generation rate calculations.
     */
    class DetailedTokenTimer {
        private var prefillStartMs: Long? = null
        private var firstTokenMs: Long? = null
        private var lastTokenMs: Long? = null
        private var tokenCount = 0
        
        fun startPrefill() {
            prefillStartMs = currentTimeMillis()
            firstTokenMs = null
            lastTokenMs = null
            tokenCount = 0
        }
        
        fun onFirstToken() {
            if (firstTokenMs == null) {
                firstTokenMs = currentTimeMillis()
            }
            tokenCount = 1
        }
        
        fun onToken() {
            lastTokenMs = currentTimeMillis()
            tokenCount++
        }
        
        /**
         * Build detailed metrics separating prefill (TTFT) from decode performance.
         */
        fun buildDetailedMetrics(): DetailedBenchmarkMetrics? {
            val prefill = prefillStartMs ?: return null
            val first = firstTokenMs ?: return null
            val last = lastTokenMs ?: first // Handle single-token case
            
            val ttftMs = first - prefill
            val decodeTps = if (tokenCount > 1) {
                (tokenCount - 1) * 1000.0 / (last - first)
            } else {
                0.0
            }
            
            return DetailedBenchmarkMetrics(
                ttftMs = ttftMs,
                decodeTps = decodeTps,
                totalTokens = tokenCount,
                totalTimeMs = last - prefill,
                prefillTimeMs = ttftMs,
                decodeTimeMs = if (tokenCount > 1) last - first else 0L
            )
        }
    }
    
    /**
     * Benchmark results with separated prefill and decode metrics.
     */
    data class DetailedBenchmarkMetrics(
        val ttftMs: Long,           // Time to first token (prefill)
        val decodeTps: Double,      // Pure decode rate (excluding TTFT)
        val totalTokens: Int,       // Total tokens generated
        val totalTimeMs: Long,      // Total generation time
        val prefillTimeMs: Long,    // Time spent in prefill phase
        val decodeTimeMs: Long      // Time spent in decode phase
    ) {
        val totalTps: Double = if (totalTimeMs > 0) {
            totalTokens * 1000.0 / totalTimeMs
        } else {
            0.0
        }
        
        fun summary(): String = 
            "TTFT=${ttftMs}ms, decode=$decodeTps tps, " +
            "total=$totalTps tps, tokens=$totalTokens"
    }
    
    /**
     * Generate standardized benchmark configuration string for logging.
     * New optional params default to neutral values so existing call sites compile unchanged.
     */
    fun formatRuntimeConfig(
        threads: Int,
        batchThreads: Int,
        batchSize: Int,
        contextLimit: Int,
        gpuLayers: Int,
        typeK: Int = 1,
        typeV: Int = 1,
        flashAttn: Int = -1,
        kleidiOn: Boolean = true,
        armArch: String = "",
        cpuMask: String = "",
        backendDl: Boolean = false,
        mlock: Boolean = false,
        modelArch: String = "",
    ): String = buildString {
        append("t=$threads/$batchThreads,b=$batchSize,ctx=$contextLimit,gpu=$gpuLayers")
        append(",kv=$typeK/$typeV,fa=$flashAttn")
        if (kleidiOn) append(",kleidi=1")
        if (armArch.isNotEmpty()) append(",arch=$armArch")
        if (cpuMask.isNotEmpty()) append(",mask=$cpuMask")
        if (backendDl) append(",bdl=1")
        if (mlock) append(",mlock=1")
        if (modelArch.isNotEmpty()) append(",model=$modelArch")
    }
}