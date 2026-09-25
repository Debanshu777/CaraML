package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.data.settings.SettingsRepository
import com.debanshu777.caraml.core.platform.BackendCapability
import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.BackendStatus
import com.debanshu777.caraml.core.platform.DeviceSnapshot
import com.debanshu777.caraml.core.platform.HardwareProfile
import com.debanshu777.caraml.core.platform.MemoryTopology
import com.debanshu777.caraml.core.platform.PowerPolicyState
import com.debanshu777.caraml.core.platform.ResourceSnapshot
import com.debanshu777.caraml.core.platform.ThermalState
import com.debanshu777.caraml.core.recommendation.storage.RecommendationObservationDao
import com.debanshu777.caraml.core.recommendation.storage.RecommendationObservationEntity
import com.debanshu777.caraml.core.settings.AppSettings
import com.debanshu777.runner.BackendCalibrationAbandonment
import com.debanshu777.runner.BackendCalibrationMetric
import com.debanshu777.runner.BackendCalibrationResult
import com.debanshu777.runner.BackendCalibrationWindow
import com.debanshu777.runner.NativeBackendKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.awaitCancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class QuickCalibrationRunnerTest {
    @Test
    fun pressureDefersWithoutStartingOrCompletingTheOffer() = runTest {
        val fixture = fixture(snapshot(thermal = ThermalState.SERIOUS))

        val result = fixture.runner.runQuickCalibration()

        assertEquals(CalibrationRunResult.Deferred(CalibrationDeferralReason.THERMAL), result)
        assertEquals(0, fixture.probe.calls)
        assertFalse(fixture.settings.offerComplete)
        assertTrue(fixture.dao.rows.isEmpty())
    }

    @Test
    fun unknownPowerRequiresVisibleConfirmationThenPersistsOneCompleteWindowSet() = runTest {
        val fixture = fixture(snapshot(power = PowerPolicyState.UNKNOWN))

        assertEquals(CalibrationRunResult.RequiresConfirmation, fixture.runner.runQuickCalibration())
        assertEquals(0, fixture.probe.calls)

        val result = fixture.runner.runQuickCalibration(allowUnknownPower = true)

        assertIs<CalibrationRunResult.Completed>(result)
        assertEquals(1, fixture.probe.calls)
        assertEquals(1, fixture.dao.insertBatches)
        assertEquals(10, fixture.dao.rows.size)
        assertEquals(5, fixture.dao.rows.count { it.metricKind == MetricKind.BANDWIDTH.name })
        assertEquals(5, fixture.dao.rows.count { it.metricKind == MetricKind.COMPUTE.name })
        assertTrue(fixture.settings.offerComplete)
        assertTrue(fixture.repository.revision() > 0L)
    }

    @Test
    fun nativeCancellationNeverStoresPartialWindowsOrCompletesTheOffer() = runTest {
        val fixture = fixture(
            snapshot(),
            result = BackendCalibrationResult.Cancelled,
        )

        assertEquals(CalibrationRunResult.Cancelled, fixture.runner.runQuickCalibration())
        assertTrue(fixture.dao.rows.isEmpty())
        assertFalse(fixture.settings.offerComplete)
    }

    @Test
    fun declineCompletesTheOfferWithoutRunningTheProbe() = runTest {
        val fixture = fixture(snapshot())

        fixture.runner.skipQuickCalibration()

        assertTrue(fixture.settings.offerComplete)
        assertEquals(0, fixture.probe.calls)
        assertTrue(fixture.dao.rows.isEmpty())
    }

    @Test
    fun timeoutCancelsAStoppedCooperativeProbeWithoutAbandoningIt() = runTest {
        val dao = CapturingDao()
        val repository = CalibrationRepository(dao, ENGINE, now = { NOW }).also { it.initialize() }
        val settings = FakeSettingsRepository()
        val probe = CooperativeTimeoutProbe()
        val runner = QuickCalibrationRunner(
            snapshotSource = { snapshot() },
            probe = probe,
            repository = repository,
            settingsRepository = settings,
            engineVersion = ENGINE,
            clock = { NOW },
            timeoutMillis = 100L,
            probeTokenSource = { 41L },
        )

        assertEquals(CalibrationRunResult.TimedOut, runner.runQuickCalibration())
        assertEquals(listOf(41L), probe.cancelledTokens)
        assertTrue(probe.abandonedTokens.isEmpty())
        assertFalse(settings.offerComplete)
    }

    @Test
    fun stuckNativeProbeIsQuarantinedOnlyAfterTheCancellationGraceExpires() = runTest {
        val dao = CapturingDao()
        val repository = CalibrationRepository(dao, ENGINE, now = { NOW }).also { it.initialize() }
        val settings = FakeSettingsRepository()
        val probe = StuckNativeProbe()
        val runner = QuickCalibrationRunner(
            snapshotSource = { snapshot() },
            probe = probe,
            repository = repository,
            settingsRepository = settings,
            engineVersion = ENGINE,
            clock = { NOW },
            timeoutMillis = 100L,
            cancellationGraceMillis = 50L,
            probeTokenSource = { 42L },
        )

        assertEquals(CalibrationRunResult.Quarantined, runner.runQuickCalibration())
        assertEquals(listOf(42L), probe.cancelledTokens)
        assertEquals(listOf(42L), probe.abandonedTokens)
        assertEquals(NativeCalibrationState.QUARANTINED, runner.nativeCalibrationState())
        assertFalse(settings.offerComplete)

        assertEquals(CalibrationRunResult.Quarantined, runner.runQuickCalibration())
        assertEquals(listOf(42L), probe.cancelledTokens)
        assertEquals(listOf(42L), probe.abandonedTokens)
    }

    @Test
    fun completionAtGraceBoundaryDoesNotFalseQuarantineOrRequireRestart() = runTest {
        val dao = CapturingDao()
        val repository = CalibrationRepository(dao, ENGINE, now = { NOW }).also { it.initialize() }
        val settings = FakeSettingsRepository()
        val probe = GraceBoundaryCompletionProbe()
        val runner = QuickCalibrationRunner(
            snapshotSource = { snapshot() },
            probe = probe,
            repository = repository,
            settingsRepository = settings,
            engineVersion = ENGINE,
            clock = { NOW },
            timeoutMillis = 100L,
            cancellationGraceMillis = 50L,
            probeTokenSource = { if (probe.calls == 0) 43L else 44L },
        )

        assertEquals(CalibrationRunResult.TimedOut, runner.runQuickCalibration())
        assertEquals(listOf(43L), probe.abandonedTokens)
        assertEquals(NativeCalibrationState.AVAILABLE, runner.nativeCalibrationState())

        assertIs<CalibrationRunResult.Completed>(runner.runQuickCalibration())
        assertEquals(2, probe.calls)
        assertTrue(settings.offerComplete)
    }

    private suspend fun fixture(
        snapshot: DeviceSnapshot,
        result: BackendCalibrationResult = completedResult(),
    ): Fixture {
        val dao = CapturingDao()
        val repository = CalibrationRepository(dao, ENGINE, now = { NOW }).also { it.initialize() }
        val settings = FakeSettingsRepository()
        val probe = FakeProbe(result)
        return Fixture(
            runner = QuickCalibrationRunner(
                snapshotSource = { snapshot },
                probe = probe,
                repository = repository,
                settingsRepository = settings,
                engineVersion = ENGINE,
                clock = { NOW },
            ),
            repository = repository,
            dao = dao,
            settings = settings,
            probe = probe,
        )
    }

    private fun snapshot(
        thermal: ThermalState = ThermalState.NOMINAL,
        power: PowerPolicyState = PowerPolicyState.NORMAL,
    ) = DeviceSnapshot(
        hardwareProfile = HardwareProfile(
            cpuArchitecture = "arm64",
            logicalCoreCount = 8,
            performanceCoreCount = 4,
            instructionSets = emptySet(),
            backends = listOf(
                BackendCapability(
                    kind = BackendKind.CPU,
                    status = BackendStatus.AVAILABLE,
                    additionalAllocatableBytes = 64L shl 20,
                    availabilityConfidence = Confidence.HIGH,
                    headroomConfidence = Confidence.HIGH,
                    evidence = emptyList(),
                ),
            ),
            memoryTopology = MemoryTopology.UNKNOWN,
            evidence = emptyList(),
        ),
        resources = ResourceSnapshot(
            additionalAllocatableHostBytes = 64L shl 20,
            additionalAllocatableGpuBytes = null,
            currentProcessBytes = null,
            freeStorageBytes = null,
            osPressureReserveHostBytes = 0L,
            observedAppFootprintNoiseP95Bytes = 0L,
            platformMinimumReserveHostBytes = 0L,
            lowMemory = false,
            thermalState = thermal,
            powerPolicyState = power,
            capturedAtEpochMs = NOW,
            evidence = emptyList(),
        ),
        baseHostBudgetBytes = 64L shl 20,
        baseGpuBudgetBytes = null,
        baseSharedBudgetBytes = null,
        baseStorageBudgetBytes = null,
        isFresh = true,
        evidence = emptyList(),
    )

    private class FakeProbe(
        private val result: BackendCalibrationResult,
    ) : BackendCalibrationProbe {
        var calls = 0
        override suspend fun run(
            probeToken: Long,
            backend: NativeBackendKind,
            durationMillis: Int,
            bufferBytes: Long,
        ): BackendCalibrationResult {
            calls++
            return result
        }

        override fun cancel(probeToken: Long) = Unit

        override fun abandon(probeToken: Long) = BackendCalibrationAbandonment.NOT_ACTIVE
    }

    private class CooperativeTimeoutProbe : BackendCalibrationProbe {
        val cancelledTokens = mutableListOf<Long>()
        val abandonedTokens = mutableListOf<Long>()

        override suspend fun run(
            probeToken: Long,
            backend: NativeBackendKind,
            durationMillis: Int,
            bufferBytes: Long,
        ): BackendCalibrationResult = try {
            awaitCancellation()
        } finally {
            cancel(probeToken)
        }

        override fun cancel(probeToken: Long) {
            if (probeToken !in cancelledTokens) cancelledTokens += probeToken
        }

        override fun abandon(probeToken: Long): BackendCalibrationAbandonment {
            abandonedTokens += probeToken
            return BackendCalibrationAbandonment.NOT_ACTIVE
        }
    }

    private class StuckNativeProbe : BackendCalibrationProbe {
        val cancelledTokens = mutableListOf<Long>()
        val abandonedTokens = mutableListOf<Long>()

        override suspend fun run(
            probeToken: Long,
            backend: NativeBackendKind,
            durationMillis: Int,
            bufferBytes: Long,
        ): BackendCalibrationResult = awaitCancellation()

        override fun cancel(probeToken: Long) {
            cancelledTokens += probeToken
        }

        override fun abandon(probeToken: Long): BackendCalibrationAbandonment {
            abandonedTokens += probeToken
            return BackendCalibrationAbandonment.QUARANTINED
        }

        override fun isRunning(probeToken: Long): Boolean = true
    }

    private class GraceBoundaryCompletionProbe : BackendCalibrationProbe {
        var calls = 0
        val abandonedTokens = mutableListOf<Long>()

        override suspend fun run(
            probeToken: Long,
            backend: NativeBackendKind,
            durationMillis: Int,
            bufferBytes: Long,
        ): BackendCalibrationResult {
            calls++
            if (calls == 1) awaitCancellation()
            return completedResult()
        }

        override fun cancel(probeToken: Long) = Unit

        override fun abandon(probeToken: Long): BackendCalibrationAbandonment {
            abandonedTokens += probeToken
            return BackendCalibrationAbandonment.NOT_ACTIVE
        }

        override fun isRunning(probeToken: Long): Boolean = calls == 1
    }

    private class FakeSettingsRepository : SettingsRepository {
        var offerComplete = false
        override fun getSettings(): Flow<AppSettings> = flowOf(AppSettings())
        override suspend fun updateSettings(settings: AppSettings) = Unit
        override suspend fun updateRecommendationProfile(profile: RecommendationProfile) = Unit
        override suspend fun completeModelProfileOnboarding(profile: RecommendationProfile) = Unit
        override suspend fun completeRecommendationCalibrationOffer() {
            offerComplete = true
        }
    }

    private class CapturingDao : RecommendationObservationDao {
        val rows = mutableListOf<RecommendationObservationEntity>()
        var insertBatches = 0
        override suspend fun insertAll(samples: List<RecommendationObservationEntity>) {
            insertBatches++
            rows += samples
        }
        override suspend fun allSamples() = rows.toList()
        override suspend fun deleteOlderThan(cutoffEpochMs: Long) { rows.removeAll { it.capturedAtEpochMs < cutoffEpochMs } }
        override suspend fun deleteNewerThan(cutoffEpochMs: Long) { rows.removeAll { it.capturedAtEpochMs > cutoffEpochMs } }
        override suspend fun retainNewest(limit: Int) { while (rows.size > limit) rows.removeAt(0) }
        override suspend fun count() = rows.size
        override suspend fun countOlderThan(cutoffEpochMs: Long) = rows.count { it.capturedAtEpochMs < cutoffEpochMs }
        override suspend fun maximumCapturedAt() = rows.maxOfOrNull { it.capturedAtEpochMs }
        override suspend fun clearAll() = rows.clear()
    }

    private data class Fixture(
        val runner: QuickCalibrationRunner,
        val repository: CalibrationRepository,
        val dao: CapturingDao,
        val settings: FakeSettingsRepository,
        val probe: FakeProbe,
    )

    private companion object {
        const val ENGINE = "native-engine-v1"
        const val NOW = 10_000_000_000L

        fun completedResult() = BackendCalibrationResult.Complete(
            backend = NativeBackendKind.CPU,
            windows = List(5) { index ->
                BackendCalibrationWindow(
                    metric = BackendCalibrationMetric.MEMORY_BANDWIDTH,
                    completedUnits = 8_000_000_000L + index,
                    elapsedNanoseconds = 1_000_000L,
                )
            } + List(5) { index ->
                BackendCalibrationWindow(
                    metric = BackendCalibrationMetric.COMPUTE,
                    completedUnits = 4_000_000_000L + index,
                    elapsedNanoseconds = 1_000_000L,
                )
            },
        )
    }
}
