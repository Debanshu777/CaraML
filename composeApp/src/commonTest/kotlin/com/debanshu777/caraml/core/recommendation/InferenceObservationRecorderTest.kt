package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.recommendation.storage.RecommendationObservationDao
import com.debanshu777.caraml.core.recommendation.storage.RecommendationObservationEntity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InferenceObservationRecorderTest {
    @Test
    fun generationRecordsOnlyBoundedNumericPerformanceAndReliableHostMemory() = runTest {
        val dao = CapturingDao()
        val repository = CalibrationRepository(dao, ENGINE, now = { NOW }).also { it.initialize() }
        val samples = ArrayDeque(listOf(1_000L, 1_500L, 1_250L))
        val recorder = InferenceObservationRecorder(
            repository = repository,
            processMemory = { ReliableMemoryReading(samples.removeFirstOrNull()) },
            monotonicNanos = sequenceClock(0L, 500_000_000L),
            wallClockMillis = { NOW },
            sampleDelay = {},
        )

        val value = recorder.measureGeneration(
            key = performanceKey(),
            prediction = ObservationPrediction(performance = 8.0, hostMemoryBytes = 400.0),
        ) {
            MeasuredResult(value = "generated-secret", completedUnits = 5, outcome = ObservationOutcome.SUCCESS)
        }

        assertEquals("generated-secret", value)
        assertEquals(setOf(MetricKind.PERFORMANCE.name, MetricKind.MEMORY.name), dao.rows.map { it.metricKind }.toSet())
        assertTrue(dao.rows.all { it.predictedValue.isFinite() && it.observedValue.isFinite() })
        assertTrue(dao.rows.none { it.toString().contains("generated-secret") })
        assertTrue(dao.rows.all { it.completedUnits == 5L && it.elapsedNanoseconds == 500_000_000L })
        assertEquals(MemoryPool.HOST.stableName, dao.rows.single { it.metricKind == MetricKind.MEMORY.name }.memoryPool)
    }

    @Test
    fun unreliableOrNegativeMemoryDeltasAreDiscardedWithoutCrossPoolInference() = runTest {
        val dao = CapturingDao()
        val repository = CalibrationRepository(dao, ENGINE, now = { NOW }).also { it.initialize() }
        val samples = ArrayDeque(listOf(2_000L, 1_000L))
        val recorder = InferenceObservationRecorder(
            repository = repository,
            processMemory = { ReliableMemoryReading(samples.removeFirstOrNull(), reliable = false) },
            monotonicNanos = sequenceClock(0L, 1_000_000_000L),
            wallClockMillis = { NOW },
            sampleDelay = {},
        )

        recorder.measureLoad(
            key = performanceKey(),
            prediction = ObservationPrediction(performance = 1.0, hostMemoryBytes = 500.0),
        ) { MeasuredResult(Unit, completedUnits = 1, outcome = ObservationOutcome.SUCCESS) }

        assertEquals(listOf(MetricKind.PERFORMANCE.name), dao.rows.map { it.metricKind })
    }

    @Test
    fun durationMeasurementStoresSecondsPerCompletedUnit() = runTest {
        val dao = CapturingDao()
        val repository = CalibrationRepository(dao, ENGINE, now = { NOW }).also { it.initialize() }
        val recorder = InferenceObservationRecorder(
            repository = repository,
            processMemory = { null },
            monotonicNanos = sequenceClock(0L, 2_000_000_000L),
            wallClockMillis = { NOW },
            sampleDelay = {},
        )

        recorder.measureGeneration(
            key = performanceKey().copy(workloadBucket = "image-512x512-4"),
            prediction = ObservationPrediction(
                performance = 0.4,
                hostMemoryBytes = null,
                performanceMeasurement = PerformanceMeasurement.DURATION_PER_UNIT,
            ),
        ) {
            MeasuredResult(Unit, completedUnits = 4, outcome = ObservationOutcome.SUCCESS)
        }

        assertEquals(0.5, dao.rows.single().observedValue, 0.000_001)
    }

    @Test
    fun rateMeasurementPersistsDurationFactorAgainstRawAnalyticalRate() = runTest {
        val dao = CapturingDao()
        val repository = CalibrationRepository(dao, ENGINE, now = { NOW }).also { it.initialize() }
        val recorder = InferenceObservationRecorder(
            repository = repository,
            processMemory = { null },
            monotonicNanos = sequenceClock(0L, 9_000_000_000L),
            wallClockMillis = { NOW },
            sampleDelay = {},
        )

        recorder.measureGeneration(
            key = performanceKey(),
            prediction = ObservationPrediction(performance = 8.0, hostMemoryBytes = null),
        ) {
            MeasuredResult(
                value = Unit,
                completedUnits = 4,
                outcome = ObservationOutcome.SUCCESS,
                performanceElapsedNanoseconds = 1_000_000_000L,
            )
        }

        val row = dao.rows.single()
        assertEquals(0.125, row.predictedValue, 0.000_001)
        assertEquals(0.25, row.observedValue, 0.000_001)
        assertEquals(1_000_000_000L, row.elapsedNanoseconds)
    }

    private fun performanceKey() = CalibrationKey(
        backend = BackendKind.CPU,
        architectureFamily = "llama",
        quantizationFamily = "q4_k",
        workloadBucket = "ctx-4096",
        engineVersion = ENGINE,
    )

    private fun sequenceClock(vararg values: Long): () -> Long {
        val queue = ArrayDeque(values.toList())
        return { queue.removeFirstOrNull() ?: values.last() }
    }

    private class CapturingDao : RecommendationObservationDao {
        val rows = mutableListOf<RecommendationObservationEntity>()
        override suspend fun insertAll(samples: List<RecommendationObservationEntity>) { rows += samples }
        override suspend fun allSamples() = rows.toList()
        override suspend fun deleteOlderThan(cutoffEpochMs: Long) { rows.removeAll { it.capturedAtEpochMs < cutoffEpochMs } }
        override suspend fun deleteNewerThan(cutoffEpochMs: Long) { rows.removeAll { it.capturedAtEpochMs > cutoffEpochMs } }
        override suspend fun retainNewest(limit: Int) { while (rows.size > limit) rows.removeAt(0) }
        override suspend fun count() = rows.size
        override suspend fun countOlderThan(cutoffEpochMs: Long) = rows.count { it.capturedAtEpochMs < cutoffEpochMs }
        override suspend fun maximumCapturedAt() = rows.maxOfOrNull { it.capturedAtEpochMs }
        override suspend fun clearAll() = rows.clear()
    }

    private companion object {
        const val ENGINE = "native-engine-v1"
        const val NOW = 10_000_000_000L
    }
}
