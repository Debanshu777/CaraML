package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.recommendation.storage.RecommendationObservationEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.TimeSource

data class ReliableMemoryReading(
    val bytes: Long?,
    val reliable: Boolean = true,
)

data class ObservationPrediction(
    val performance: Double?,
    val hostMemoryBytes: Double?,
    val performanceMeasurement: PerformanceMeasurement = PerformanceMeasurement.RATE,
)

enum class PerformanceMeasurement {
    RATE,
    DURATION_PER_UNIT,
}

data class MeasuredResult<T>(
    val value: T,
    val completedUnits: Int,
    val outcome: ObservationOutcome,
)

class InferenceObservationRecorder(
    private val repository: CalibrationRepository,
    private val processMemory: suspend () -> ReliableMemoryReading?,
    private val monotonicNanos: () -> Long = defaultMonotonicNanos(),
    private val wallClockMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val sampleDelay: suspend () -> Unit = { delay(SAMPLE_INTERVAL_MS) },
) {
    suspend fun <T> measureLoad(
        key: CalibrationKey,
        prediction: ObservationPrediction,
        block: suspend () -> MeasuredResult<T>,
    ): T = measure(key, prediction, block)

    suspend fun <T> measureGeneration(
        key: CalibrationKey,
        prediction: ObservationPrediction,
        block: suspend () -> MeasuredResult<T>,
    ): T = measure(key, prediction, block)

    private suspend fun <T> measure(
        key: CalibrationKey,
        prediction: ObservationPrediction,
        block: suspend () -> MeasuredResult<T>,
    ): T = coroutineScope {
        val baseline = safeMemoryReading()
        var peak = baseline?.bytes
        val peakMutex = Mutex()
        val sampler = launch {
            var samples = 0
            while (isActive && samples < MAX_MEMORY_SAMPLES) {
                sampleDelay()
                val reading = safeMemoryReading()
                if (reading?.reliable == true && reading.bytes != null && reading.bytes >= 0L) {
                    peakMutex.withLock {
                        peak = maxOf(peak ?: reading.bytes, reading.bytes)
                    }
                }
                samples++
            }
        }
        val started = monotonicNanos()
        try {
            val measured = block()
            val ended = monotonicNanos()
            val finalReading = safeMemoryReading()
            if (finalReading?.reliable == true && finalReading.bytes != null && finalReading.bytes >= 0L) {
                peakMutex.withLock {
                    peak = maxOf(peak ?: finalReading.bytes, finalReading.bytes)
                }
            }
            val measuredPeak = peakMutex.withLock { peak }
            val observations = validatedObservations(
                key = key,
                prediction = prediction,
                measured = measured,
                elapsedNanos = ended - started,
                baseline = baseline,
                peakBytes = measuredPeak,
            )
            if (observations.isNotEmpty()) repository.recordAll(observations)
            measured.value
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            sampler.cancel()
            sampler.join()
        }
    }

    private fun <T> validatedObservations(
        key: CalibrationKey,
        prediction: ObservationPrediction,
        measured: MeasuredResult<T>,
        elapsedNanos: Long,
        baseline: ReliableMemoryReading?,
        peakBytes: Long?,
    ): List<RecommendationObservationEntity> {
        if (measured.completedUnits !in 1..MAX_COMPLETED_UNITS ||
            elapsedNanos !in 1..MAX_MEASUREMENT_NANOS
        ) return emptyList()
        val capturedAt = wallClockMillis()
        if (capturedAt < 0L) return emptyList()
        return buildList {
            val predictedPerformance = prediction.performance
            val observedPerformance = when (prediction.performanceMeasurement) {
                PerformanceMeasurement.RATE ->
                    measured.completedUnits.toDouble() * NANOS_PER_SECOND / elapsedNanos.toDouble()
                PerformanceMeasurement.DURATION_PER_UNIT ->
                    elapsedNanos.toDouble() / NANOS_PER_SECOND / measured.completedUnits.toDouble()
            }
            if (predictedPerformance.isValidMetric() && observedPerformance.isValidMetric()) {
                val validPredictedPerformance = requireNotNull(predictedPerformance)
                add(
                    RecommendationObservationEntity.from(
                        key = key.copy(metricKind = MetricKind.PERFORMANCE, memoryPool = null),
                        predictedValue = validPredictedPerformance,
                        observedValue = observedPerformance,
                        completedUnits = measured.completedUnits.toLong(),
                        elapsedNanoseconds = elapsedNanos,
                        outcome = measured.outcome,
                        capturedAtEpochMs = capturedAt,
                    ),
                )
            }
            val predictedMemory = prediction.hostMemoryBytes
            val baselineBytes = baseline?.bytes
            if (baseline?.reliable == true && baselineBytes != null && baselineBytes >= 0L &&
                peakBytes != null && peakBytes >= baselineBytes && predictedMemory.isValidMetric()
            ) {
                val delta = (peakBytes - baselineBytes).toDouble()
                if (delta.isValidMetric()) {
                    val validPredictedMemory = requireNotNull(predictedMemory)
                    add(
                        RecommendationObservationEntity.from(
                            key = key.copy(metricKind = MetricKind.MEMORY, memoryPool = MemoryPool.HOST.stableName),
                            predictedValue = validPredictedMemory,
                            observedValue = delta,
                            completedUnits = measured.completedUnits.toLong(),
                            elapsedNanoseconds = elapsedNanos,
                            outcome = measured.outcome,
                            capturedAtEpochMs = capturedAt,
                        ),
                    )
                }
            }
        }
    }

    private suspend fun safeMemoryReading(): ReliableMemoryReading? = try {
        processMemory()?.takeIf { reading ->
            reading.reliable && reading.bytes?.let { it >= 0L } == true
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun Double?.isValidMetric(): Boolean = this != null && isFinite() && this > 0.0 && this <= MAX_METRIC

    companion object {
        const val SAMPLE_INTERVAL_MS = 100L
        private const val MAX_MEMORY_SAMPLES = 36_000
        private const val MAX_COMPLETED_UNITS = 1_000_000_000
        private const val MAX_MEASUREMENT_NANOS = 24L * 60L * 60L * 1_000_000_000L
        private const val NANOS_PER_SECOND = 1_000_000_000.0
        private const val MAX_METRIC = 1.0e18

        private fun defaultMonotonicNanos(): () -> Long {
            val origin = TimeSource.Monotonic.markNow()
            return { origin.elapsedNow().inWholeNanoseconds }
        }
    }
}
