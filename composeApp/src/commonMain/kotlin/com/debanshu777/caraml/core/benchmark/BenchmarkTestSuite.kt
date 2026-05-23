package com.debanshu777.caraml.core.benchmark

import com.debanshu777.caraml.core.data.Inference.InferenceRepository
import com.debanshu777.caraml.features.chat.data.InferenceMetrics
import com.debanshu777.caraml.features.chat.domain.usecase.GenerateResponseUseCase
import com.debanshu777.caraml.features.chat.data.currentTimeMillis

data class BenchmarkRunResult(
    val runNumber: Int,
    val metrics: InferenceMetrics?,
    val wallClockMs: Long,
    val stopReason: Int = 0,
)

class BenchmarkTestSuite(
    private val generateResponseUseCase: GenerateResponseUseCase,
    private val inferenceRepository: InferenceRepository,
) {
    suspend fun runStandardBenchmark(runs: Int = 5): List<BenchmarkRunResult> {
        BenchmarkUtils.enableBenchmarkMode()
        val results = mutableListOf<BenchmarkRunResult>()
        for (run in 1..runs) {
            inferenceRepository.resetContext()
            val start = currentTimeMillis()
            val result = generateResponseUseCase(BenchmarkUtils.STANDARD_PROMPT) { _, _, _ -> }
            results.add(
                BenchmarkRunResult(
                    runNumber = run,
                    metrics = result.metrics,
                    wallClockMs = currentTimeMillis() - start,
                    stopReason = result.stopReason,
                )
            )
            if (run < runs) BenchmarkUtils.standardCooldown()
        }
        BenchmarkUtils.disableBenchmarkMode()
        return results
    }
}
