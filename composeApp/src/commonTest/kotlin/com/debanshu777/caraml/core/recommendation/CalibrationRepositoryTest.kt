package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.recommendation.storage.RecommendationObservationDao
import com.debanshu777.caraml.core.recommendation.storage.RecommendationObservationEntity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalibrationRepositoryTest {
    @Test
    fun correctionNeedsFiveSamplesAndHighMemoryNeverFallsBelowOne() = runTest {
        val dao = FakeObservationDao()
        val repository = CalibrationRepository(dao, ENGINE, now = { NOW })
        repository.initialize()
        repeat(4) { repository.record(sample(observedRatio = 0.8)) }
        assertNull(repository.correctionFor(memoryKey()))

        repository.record(sample(observedRatio = 0.8))

        val correction = requireNotNull(repository.correctionFor(memoryKey()))
        assertEquals(0.8, correction.likely, 0.000_001)
        assertEquals(1.0, correction.high, 0.000_001)
    }

    @Test
    fun correctionsUseOnlyTheExactVersionedPoolKey() = runTest {
        val repository = CalibrationRepository(FakeObservationDao(), ENGINE, now = { NOW })
        repository.initialize()
        repeat(5) { repository.record(sample(observedRatio = 1.25)) }

        assertNull(repository.correctionFor(memoryKey().copy(engineVersion = "engine-2")))
        assertNull(repository.correctionFor(memoryKey().copy(estimatorVersion = 2)))
        assertNull(repository.correctionFor(memoryKey().copy(memoryPool = MemoryPool.SHARED.stableName)))
        assertNull(repository.correctionFor(memoryKey().copy(metricKind = MetricKind.PERFORMANCE, memoryPool = null)))
        assertEquals(1.25, repository.correctionFor(memoryKey())?.likely)
    }

    @Test
    fun recentSimilarSamplesDominateExpiredOrDissimilarRows() = runTest {
        val dao = FakeObservationDao()
        val repository = CalibrationRepository(dao, ENGINE, now = { NOW })
        repository.initialize()
        repeat(5) { dao.insertAll(listOf(sample(observedRatio = 4.0, capturedAt = NOW - 89 * DAY, similarity = 0.05))) }
        repeat(5) { dao.insertAll(listOf(sample(observedRatio = 1.2, capturedAt = NOW, similarity = 1.0))) }
        repository.initialize()

        val correction = requireNotNull(repository.correctionFor(memoryKey()))

        assertEquals(1.2, correction.likely, 0.000_001)
        assertEquals(1.2, correction.high, 0.000_001)
    }

    @Test
    fun backendProfilePreservesMedianAndLowerThroughputTail() = runTest {
        val repository = CalibrationRepository(FakeObservationDao(), ENGINE, now = { NOW })
        repository.initialize()
        listOf(10.0, 100.0, 100.0, 100.0, 100.0).forEach { value ->
            assertTrue(repository.record(profileSample(MetricKind.BANDWIDTH, value)))
            assertTrue(repository.record(profileSample(MetricKind.COMPUTE, value * 1_000.0)))
        }

        val profile = requireNotNull(repository.backendProfileFor(BackendKind.CPU))

        assertEquals(100.0, profile.sustainedBytesPerSecond, 0.000_001)
        assertEquals(10.0, profile.conservativeBytesPerSecond, 0.000_001)
        assertEquals(100_000.0, profile.sustainedOperationsPerSecond, 0.000_001)
        assertEquals(10_000.0, profile.conservativeOperationsPerSecond, 0.000_001)
    }

    @Test
    fun recordRejectsMalformedExternalValuesWithoutChangingRevision() = runTest {
        val dao = FakeObservationDao()
        val repository = CalibrationRepository(dao, ENGINE, now = { NOW })
        repository.initialize()
        val before = repository.revision()

        assertEquals(false, repository.record(sample(observedRatio = Double.NaN)))
        assertEquals(false, repository.record(sample(observedRatio = 1.0).copy(backend = "unknown-backend")))

        assertEquals(0, dao.count())
        assertEquals(before, repository.revision())
    }

    @Test
    fun failedTransactionDoesNotPublishRowsOrAdvanceRevision() = runTest {
        val dao = FakeObservationDao(failWrites = true)
        val repository = CalibrationRepository(dao, ENGINE, now = { NOW })
        repository.initialize()

        val accepted = repository.record(sample(observedRatio = 1.1))

        assertEquals(false, accepted)
        assertEquals(0L, repository.revision())
        assertNull(repository.correctionFor(memoryKey()))
    }

    @Test
    fun pruningRetainsFiveHundredRecentRowsForNinetyDays() = runTest {
        val dao = FakeObservationDao()
        val repository = CalibrationRepository(dao, ENGINE, now = { NOW })
        repository.initialize()
        dao.insertAll((0 until 600).map { index ->
            sample(observedRatio = 1.0, capturedAt = NOW - index * 1_000L)
        } + sample(observedRatio = 1.0, capturedAt = NOW - 91 * DAY))

        assertTrue(repository.prune(NOW))

        assertEquals(500, dao.count())
        assertEquals(0, dao.countOlderThan(NOW - 90 * DAY))
        assertTrue(repository.revision() > 0L)
    }

    @Test
    fun initializeTransactionPrunesExpiredAndFutureRowsBeforePublishing() = runTest {
        val dao = FakeObservationDao()
        dao.insertAll(
            listOf(
                sample(1.1, capturedAt = NOW),
                sample(3.0, capturedAt = NOW - 91 * DAY),
                sample(4.0, capturedAt = NOW + 300_001L),
            ),
        )

        val repository = CalibrationRepository(dao, ENGINE, now = { NOW })
        repository.initialize()

        assertEquals(1, dao.count())
        assertEquals(NOW, dao.maximumCapturedAt())
    }

    @Test
    fun initializeRecoversOnceFromDisposableStoreCorruption() = runTest {
        val corrupt = FakeObservationDao(failReads = true)
        val recovered = FakeObservationDao().also {
            repeat(5) { ignored -> it.insertAll(listOf(sample(1.25))) }
        }
        var recoveryCalls = 0
        val repository = CalibrationRepository(
            dao = corrupt,
            currentEngineVersion = ENGINE,
            now = { NOW },
            recoverDao = {
                recoveryCalls++
                recovered
            },
        )

        repository.initialize()

        assertEquals(1, recoveryCalls)
        assertEquals(1.25, repository.correctionFor(memoryKey())?.likely)
    }

    @Test
    fun initializeTreatsMalformedStoredRowsAsDisposableCacheCorruption() = runTest {
        val malformed = FakeObservationDao().also {
            it.insertAll(listOf(sample(1.0).copy(backend = "unknown-backend")))
        }
        val recovered = FakeObservationDao()
        var recoveryCalls = 0
        val repository = CalibrationRepository(
            dao = malformed,
            currentEngineVersion = ENGINE,
            now = { NOW },
            recoverDao = {
                recoveryCalls++
                recovered
            },
        )

        repository.initialize()

        assertEquals(1, recoveryCalls)
        assertEquals(0L, repository.revision())
    }

    private fun memoryKey() = CalibrationKey(
        backend = BackendKind.CPU,
        architectureFamily = "llama",
        quantizationFamily = "q4_k",
        workloadBucket = "ctx-4096",
        engineVersion = ENGINE,
        estimatorVersion = 1,
        metricKind = MetricKind.MEMORY,
        memoryPool = MemoryPool.HOST.stableName,
    )

    private fun sample(
        observedRatio: Double,
        capturedAt: Long = NOW,
        similarity: Double = 1.0,
    ) = RecommendationObservationEntity.from(
        key = memoryKey(),
        predictedValue = 100.0,
        observedValue = 100.0 * observedRatio,
        outcome = ObservationOutcome.SUCCESS,
        capturedAtEpochMs = capturedAt,
        similarity = similarity,
    )

    private fun profileSample(kind: MetricKind, observedValue: Double) = RecommendationObservationEntity.from(
        key = memoryKey().copy(metricKind = kind, memoryPool = null),
        predictedValue = observedValue,
        observedValue = observedValue,
        completedUnits = 1L,
        elapsedNanoseconds = 1L,
        outcome = ObservationOutcome.SUCCESS,
        capturedAtEpochMs = NOW,
    )

    private class FakeObservationDao(
        private val failWrites: Boolean = false,
        private val failReads: Boolean = false,
    ) : RecommendationObservationDao {
        private val rows = mutableListOf<RecommendationObservationEntity>()
        private var nextId = 1L

        override suspend fun insertAll(samples: List<RecommendationObservationEntity>) {
            if (failWrites) error("write failed")
            rows += samples.map { it.copy(id = nextId++) }
        }

        override suspend fun allSamples(): List<RecommendationObservationEntity> {
            if (failReads) error("database corrupt")
            return rows.toList()
        }
        override suspend fun deleteOlderThan(cutoffEpochMs: Long) {
            rows.removeAll { it.capturedAtEpochMs < cutoffEpochMs }
        }
        override suspend fun deleteNewerThan(cutoffEpochMs: Long) {
            rows.removeAll { it.capturedAtEpochMs > cutoffEpochMs }
        }
        override suspend fun retainNewest(limit: Int) {
            val retained = rows.sortedWith(compareByDescending<RecommendationObservationEntity> { it.capturedAtEpochMs }.thenByDescending { it.id }).take(limit).toSet()
            rows.retainAll(retained)
        }
        override suspend fun count(): Int = rows.size
        override suspend fun countOlderThan(cutoffEpochMs: Long): Int = rows.count { it.capturedAtEpochMs < cutoffEpochMs }
        override suspend fun maximumCapturedAt(): Long? = rows.maxOfOrNull { it.capturedAtEpochMs }
        override suspend fun clearAll() = rows.clear()
    }

    private companion object {
        const val ENGINE = "native-engine-v1"
        const val NOW = 10_000_000_000L
        const val DAY = 86_400_000L
    }
}
