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
import com.debanshu777.runner.BackendCalibrationResult
import com.debanshu777.runner.BackendCalibrationWindow
import com.debanshu777.runner.NativeBackendKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
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
            backend: NativeBackendKind,
            durationMillis: Int,
            bufferBytes: Long,
        ): BackendCalibrationResult {
            calls++
            return result
        }

        override fun cancel() = Unit
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
                    bytesMoved = 8_000_000_000L + index,
                    operations = 4_000_000_000L + index,
                    elapsedNanoseconds = 1_000_000L,
                )
            },
        )
    }
}
