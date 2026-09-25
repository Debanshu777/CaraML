package com.debanshu777.caraml.core.recommendation

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Clock

class RecommendationPerformanceTest {
    @Test
    fun cachedRerankAndAnalyticalAssessmentRespectPinnedRunnerBudgets() {
        val policy = RecommendationPolicy()
        val snapshot = task6Snapshot(capturedAtEpochMs = Clock.System.now().toEpochMilliseconds())
        val profile = RecommendationProfile()
        val assessments = List(CACHED_CANDIDATE_COUNT) { index ->
            task6Assessment(
                assessmentKey = "perf-fixture:$index",
                plans = listOf(
                    task6PlanAssessment(
                        host = task6Range(
                            100L + index,
                            150L + index,
                            200L + index,
                        ),
                    ),
                ),
            )
        }
        val estimator = LlmFootprintEstimator()
        val descriptor = task6LlmDescriptor()
        val plan = task6LlmPlan()

        repeat(WARM_UP_ITERATIONS) {
            rerank(policy, assessments, snapshot, profile)
            estimator.estimate(descriptor, plan, MemoryCalibration.None)
        }

        val rerankSamples = LongArray(ANALYTICAL_ITERATIONS) {
            measuredNanos { rerank(policy, assessments, snapshot, profile) }
        }
        val analyticalSamples = LongArray(ANALYTICAL_ITERATIONS) {
            measuredNanos { estimator.estimate(descriptor, plan, MemoryCalibration.None) }
        }
        val rerankP95 = percentile95(rerankSamples)
        val analyticalP95 = percentile95(analyticalSamples)
        println(
            "recommendation-performance cached-rerank-p95-ns=$rerankP95 " +
                "analytical-p95-ns=$analyticalP95 samples=$ANALYTICAL_ITERATIONS",
        )

        assertTrue(rerankP95 > 0L)
        assertTrue(analyticalP95 > 0L)
        if (enforcementRequested()) {
            val runnerId = System.getenv(BENCHMARK_RUNNER_ID_ENV).orEmpty()
            assertTrue(runnerId.matches(PINNED_RUNNER_ID), "A stable benchmark runner ID is required")
            assertTrue(rerankP95 <= CACHED_RERANK_P95_BUDGET_NS, "cached rerank p95=$rerankP95")
            assertTrue(analyticalP95 <= ANALYTICAL_P95_BUDGET_NS, "analytical p95=$analyticalP95")
        }
    }

    private fun rerank(
        policy: RecommendationPolicy,
        assessments: List<ModelAssessment>,
        snapshot: com.debanshu777.caraml.core.platform.DeviceSnapshot,
        profile: RecommendationProfile,
    ): List<ModelAssessment> = assessments.sortedBy { policy.sortKey(it, snapshot, profile) }

    private inline fun measuredNanos(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start).coerceAtLeast(1L)
    }

    private fun percentile95(samples: LongArray): Long {
        require(samples.isNotEmpty())
        val sorted = samples.sortedArray()
        val index = ((sorted.size - 1) * 95) / 100
        return sorted[index]
    }

    private fun enforcementRequested(): Boolean =
        System.getenv(ENFORCE_PERFORMANCE_ENV)?.equals("true", ignoreCase = true) == true

    private companion object {
        const val CACHED_CANDIDATE_COUNT = 100
        const val ANALYTICAL_ITERATIONS = 1_000
        const val WARM_UP_ITERATIONS = 100
        const val CACHED_RERANK_P95_BUDGET_NS = 100_000_000L
        const val ANALYTICAL_P95_BUDGET_NS = 2_000_000L
        const val ENFORCE_PERFORMANCE_ENV = "CARAML_ENFORCE_RECOMMENDATION_PERF"
        const val BENCHMARK_RUNNER_ID_ENV = "CARAML_BENCHMARK_RUNNER_ID"
        val PINNED_RUNNER_ID = Regex("[A-Za-z0-9._-]{1,64}")
    }
}
