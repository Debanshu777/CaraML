package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.recommendation.storage.RecommendationObservationDao
import com.debanshu777.caraml.core.recommendation.storage.RecommendationObservationEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.math.exp
import kotlin.math.max

enum class MemoryPool(val stableName: String) {
    HOST("host"),
    DISCRETE_GPU("discrete_gpu"),
    SHARED("shared"),
}

enum class ObservationOutcome {
    SUCCESS,
    ALLOCATION_FAILURE,
    SUSPECTED_LOAD_CRASH,
}

class CalibrationRepository(
    private val dao: RecommendationObservationDao,
    private val currentEngineVersion: String,
    private val now: () -> Long,
) : CalibrationSource {
    private val writeMutex = Mutex()

    @Volatile
    private var snapshot = Snapshot(emptyList(), 0L)

    suspend fun initialize() {
        writeMutex.withLock {
            try {
                val rows = dao.allSamples().filter(::isValidStoredSample).take(MAX_OBSERVATIONS)
                snapshot = Snapshot(rows, dao.maximumCapturedAt()?.coerceAtLeast(0L) ?: 0L)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                snapshot = Snapshot(emptyList(), 0L)
            }
        }
    }

    suspend fun record(sample: RecommendationObservationEntity): Boolean = recordAll(listOf(sample))

    suspend fun recordAll(samples: List<RecommendationObservationEntity>): Boolean {
        if (samples.isEmpty() || samples.size > MAX_TRANSACTION_SAMPLES || samples.any { !isValidNewSample(it) }) {
            return false
        }
        return writeMutex.withLock {
            try {
                val currentTime = now().takeIf { it >= 0L } ?: return@withLock false
                val committedRows = dao.insertAndPrune(samples, retentionCutoff(currentTime), MAX_OBSERVATIONS)
                publishCommittedRows(committedRows, currentTime)
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
        }
    }

    suspend fun prune(atEpochMs: Long = now()): Boolean {
        if (atEpochMs < 0L) return false
        return writeMutex.withLock {
            try {
                val committedRows = dao.pruneTransaction(retentionCutoff(atEpochMs), MAX_OBSERVATIONS)
                publishCommittedRows(committedRows, atEpochMs)
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
        }
    }

    suspend fun reset(): Boolean = writeMutex.withLock {
        try {
            dao.resetTransaction()
            publishCommittedRows(emptyList(), now().coerceAtLeast(0L))
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    override fun engineVersion(): String = currentEngineVersion

    override fun correctionFor(key: CalibrationKey): CalibrationCorrection? {
        if (!isValidKey(key)) return null
        val currentTime = now().takeIf { it >= 0L } ?: return null
        val weighted = snapshot.rows.asSequence()
            .filter { it.matches(key) && it.outcome == ObservationOutcome.SUCCESS.name }
            .mapNotNull { row -> row.toWeightedRatio(currentTime) }
            .toList()
        if (weighted.size < MIN_COMPARABLE_SAMPLES) return null
        val likely = weightedQuantile(weighted, 0.5) ?: return null
        var high = weightedQuantile(weighted, 0.9) ?: return null
        if (key.metricKind == MetricKind.MEMORY) high = max(1.0, high)
        high = max(likely, high)
        val confidence = if (weighted.size >= HIGH_CONFIDENCE_SAMPLES) Confidence.HIGH else Confidence.MEDIUM
        return CalibrationCorrection(likely = likely, high = high, confidence = confidence)
    }

    override fun backendProfileFor(backend: BackendKind): BackendPerformanceProfile? {
        val currentTime = now().takeIf { it >= 0L } ?: return null
        val bandwidth = profileMetric(backend, MetricKind.BANDWIDTH, currentTime) ?: return null
        val compute = profileMetric(backend, MetricKind.COMPUTE, currentTime) ?: return null
        return BackendPerformanceProfile(
            sustainedBytesPerSecond = bandwidth.value,
            sustainedOperationsPerSecond = compute.value,
            confidence = if (minOf(bandwidth.samples, compute.samples) >= HIGH_CONFIDENCE_SAMPLES) {
                Confidence.HIGH
            } else {
                Confidence.MEDIUM
            },
            evidence = listOf(
                Evidence(
                    AssessmentReason.PERFORMANCE_CALIBRATED,
                    Confidence.MEDIUM,
                    "local-backend-calibration",
                ),
            ),
        )
    }

    override fun revision(): Long = snapshot.revision

    private fun profileMetric(backend: BackendKind, kind: MetricKind, currentTime: Long): ProfileMetric? {
        val values = snapshot.rows.asSequence()
            .filter {
                it.engineVersion == currentEngineVersion &&
                    it.estimatorVersion == PerformanceEstimator.VERSION &&
                    it.backend == backend.name &&
                    it.metricKind == kind.name &&
                    it.memoryPool == null &&
                    it.outcome == ObservationOutcome.SUCCESS.name
            }
            .mapNotNull { row -> row.toWeightedValue(currentTime) }
            .toList()
        if (values.size < MIN_COMPARABLE_SAMPLES) return null
        return weightedQuantile(values, 0.5)?.let { ProfileMetric(it, values.size) }
    }

    private fun publishCommittedRows(rows: List<RecommendationObservationEntity>, commitStamp: Long) {
        val valid = rows.filter(::isValidStoredSample).take(MAX_OBSERVATIONS)
        val old = snapshot.revision
        val persisted = valid.maxOfOrNull { it.capturedAtEpochMs } ?: 0L
        val advanced = if (old == Long.MAX_VALUE) Long.MAX_VALUE else old + 1L
        val next = max(max(advanced, persisted), commitStamp.coerceAtMost(Long.MAX_VALUE - 1L))
        snapshot = Snapshot(valid, next)
    }

    private fun isValidNewSample(row: RecommendationObservationEntity): Boolean {
        val currentTime = now()
        return row.id == 0L && row.engineVersion == currentEngineVersion &&
            row.capturedAtEpochMs <= currentTime + MAX_FUTURE_SKEW_MS && isValidStoredSample(row)
    }

    private fun isValidStoredSample(row: RecommendationObservationEntity): Boolean {
        val backend = BackendKind.entries.firstOrNull { it.name == row.backend } ?: return false
        val metric = MetricKind.entries.firstOrNull { it.name == row.metricKind } ?: return false
        if (backend.name != row.backend || ObservationOutcome.entries.none { it.name == row.outcome }) return false
        if (!validStableLabel(row.engineVersion) || !validStableLabel(row.architectureFamily) ||
            !validStableLabel(row.quantFamily) || !validStableLabel(row.workloadBucket)
        ) return false
        if (row.estimatorVersion !in 1..MAX_ESTIMATOR_VERSION || row.capturedAtEpochMs < 0L) return false
        if (!validNumber(row.predictedValue) || !validNumber(row.observedValue)) return false
        if ((row.completedUnits == null) != (row.elapsedNanoseconds == null)) return false
        if (row.completedUnits?.let { it !in 1L..MAX_COMPLETED_UNITS } == true ||
            row.elapsedNanoseconds?.let { it !in 1L..MAX_ELAPSED_NANOS } == true
        ) return false
        if (!row.similarity.isFinite() || row.similarity !in MIN_SIMILARITY..1.0) return false
        return if (metric == MetricKind.MEMORY) {
            row.memoryPool != null && MemoryPool.entries.any { it.stableName == row.memoryPool }
        } else {
            row.memoryPool == null
        }
    }

    private fun isValidKey(key: CalibrationKey): Boolean =
        key.engineVersion == currentEngineVersion &&
            key.estimatorVersion in 1..MAX_ESTIMATOR_VERSION &&
            validStableLabel(key.engineVersion) && validStableLabel(key.architectureFamily) &&
            validStableLabel(key.quantizationFamily) && validStableLabel(key.workloadBucket) &&
            if (key.metricKind == MetricKind.MEMORY) {
                key.memoryPool != null && MemoryPool.entries.any { it.stableName == key.memoryPool }
            } else {
                key.memoryPool == null
            }

    private fun RecommendationObservationEntity.matches(key: CalibrationKey): Boolean =
        engineVersion == key.engineVersion && estimatorVersion == key.estimatorVersion &&
            backend == key.backend.name && architectureFamily == key.architectureFamily &&
            quantFamily == key.quantizationFamily && workloadBucket == key.workloadBucket &&
            metricKind == key.metricKind.name && memoryPool == key.memoryPool

    private fun RecommendationObservationEntity.toWeightedRatio(currentTime: Long): WeightedValue? {
        val ratio = observedValue / predictedValue
        return ratio.takeIf(::validNumber)?.let { WeightedValue(it, weightAt(currentTime)) }
            ?.takeIf { it.weight.isFinite() && it.weight > 0.0 }
    }

    private fun RecommendationObservationEntity.toWeightedValue(currentTime: Long): WeightedValue? =
        WeightedValue(observedValue, weightAt(currentTime)).takeIf { it.weight.isFinite() && it.weight > 0.0 }

    private fun RecommendationObservationEntity.weightAt(currentTime: Long): Double {
        val ageMs = (currentTime - capturedAtEpochMs).coerceAtLeast(0L)
        return similarity * exp(-(ageMs.toDouble() / DAY_MS.toDouble()) / DECAY_DAYS)
    }

    private fun weightedQuantile(values: List<WeightedValue>, quantile: Double): Double? {
        if (values.isEmpty() || quantile !in 0.0..1.0) return null
        val sorted = values.sortedBy(WeightedValue::value)
        val total = sorted.sumOf(WeightedValue::weight)
        if (!total.isFinite() || total <= 0.0) return null
        val target = total * quantile
        var cumulative = 0.0
        sorted.forEach {
            cumulative += it.weight
            if (cumulative >= target) return it.value
        }
        return sorted.last().value
    }

    private fun retentionCutoff(atEpochMs: Long): Long =
        (atEpochMs - RETENTION_MS).coerceAtLeast(0L)

    private fun validStableLabel(value: String): Boolean =
        value.length in 1..MAX_LABEL_LENGTH && STABLE_LABEL.matches(value)

    private fun validNumber(value: Double): Boolean = value.isFinite() && value > 0.0 && value <= MAX_NUMERIC_VALUE

    private data class Snapshot(val rows: List<RecommendationObservationEntity>, val revision: Long)
    private data class WeightedValue(val value: Double, val weight: Double)
    private data class ProfileMetric(val value: Double, val samples: Int)

    companion object {
        const val MIN_COMPARABLE_SAMPLES = 5
        const val MAX_OBSERVATIONS = 500
        const val RETENTION_DAYS = 90L
        private const val HIGH_CONFIDENCE_SAMPLES = 20
        private const val DECAY_DAYS = 30.0
        private const val DAY_MS = 86_400_000L
        private const val RETENTION_MS = RETENTION_DAYS * DAY_MS
        private const val MAX_FUTURE_SKEW_MS = 300_000L
        private const val MAX_TRANSACTION_SAMPLES = 64
        private const val MAX_LABEL_LENGTH = 128
        private const val MAX_ESTIMATOR_VERSION = 1_000_000
        private const val MAX_NUMERIC_VALUE = 1.0e18
        private const val MAX_COMPLETED_UNITS = Long.MAX_VALUE / 4L
        private const val MAX_ELAPSED_NANOS = 24L * 60L * 60L * 1_000_000_000L
        private const val MIN_SIMILARITY = 0.05
        private val STABLE_LABEL = Regex("[A-Za-z0-9][A-Za-z0-9._+:/-]*")
    }
}
