package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.data.settings.SettingsRepository
import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.BackendStatus
import com.debanshu777.caraml.core.platform.DeviceSnapshot
import com.debanshu777.caraml.core.platform.PowerPolicyState
import com.debanshu777.caraml.core.platform.ThermalState
import com.debanshu777.caraml.core.recommendation.storage.RecommendationObservationEntity
import com.debanshu777.runner.BackendCalibrationResult
import com.debanshu777.runner.BackendCalibrationMetric
import com.debanshu777.runner.LlamaRunner
import com.debanshu777.runner.NativeBackendKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

enum class CalibrationDeferralReason {
    THERMAL,
    POWER_SAVER,
    LOW_MEMORY,
    BACKEND_UNAVAILABLE,
    INSUFFICIENT_MEMORY,
}

sealed interface CalibrationRunResult {
    data class Completed(val backend: BackendKind) : CalibrationRunResult
    data class Deferred(val reason: CalibrationDeferralReason) : CalibrationRunResult
    data object RequiresConfirmation : CalibrationRunResult
    data object TimedOut : CalibrationRunResult
    data object Cancelled : CalibrationRunResult
    data object Failed : CalibrationRunResult
}

interface BackendCalibrationProbe {
    suspend fun run(
        probeToken: Long,
        backend: NativeBackendKind,
        durationMillis: Int,
        bufferBytes: Long,
    ): BackendCalibrationResult

    fun cancel(probeToken: Long)
}

private val backendCalibrationAdmission = Mutex()

@OptIn(ExperimentalAtomicApi::class)
class LlamaBackendCalibrationProbe internal constructor(
    private val dispatcher: CoroutineDispatcher,
    private val calibrateBackend: (Long, NativeBackendKind, Int, Long) -> BackendCalibrationResult,
    private val cancelBackendCalibration: (Long) -> Unit,
) : BackendCalibrationProbe {
    constructor(
        runner: LlamaRunner,
        dispatcher: CoroutineDispatcher,
    ) : this(
        dispatcher = dispatcher,
        calibrateBackend = runner::calibrateBackend,
        cancelBackendCalibration = runner::cancelBackendCalibration,
    )

    private val workerScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val activeState = AtomicLong(NO_ACTIVE_PROBE)

    override suspend fun run(
        probeToken: Long,
        backend: NativeBackendKind,
        durationMillis: Int,
        bufferBytes: Long,
    ): BackendCalibrationResult {
        if (probeToken <= 0L) return BackendCalibrationResult.Invalid
        val work = workerScope.async {
            backendCalibrationAdmission.withLock {
                currentCoroutineContext().ensureActive()
                if (!activeState.compareAndSet(NO_ACTIVE_PROBE, probeToken)) {
                    return@withLock BackendCalibrationResult.Unavailable
                }
                try {
                    calibrateBackend(probeToken, backend, durationMillis, bufferBytes)
                } finally {
                    if (!activeState.compareAndSet(probeToken, NO_ACTIVE_PROBE)) {
                        activeState.compareAndSet(-probeToken, NO_ACTIVE_PROBE)
                    }
                }
            }
        }
        return try {
            work.await()
        } catch (cancelled: CancellationException) {
            cancel(probeToken)
            work.cancel()
            throw cancelled
        } catch (_: Exception) {
            BackendCalibrationResult.Unavailable
        }
    }

    override fun cancel(probeToken: Long) {
        if (probeToken > 0L && activeState.compareAndSet(probeToken, -probeToken)) {
            cancelBackendCalibration(probeToken)
        }
    }

    private companion object {
        const val NO_ACTIVE_PROBE = 0L
    }
}

@OptIn(ExperimentalAtomicApi::class)
class QuickCalibrationRunner(
    private val snapshotSource: suspend () -> DeviceSnapshot,
    private val probe: BackendCalibrationProbe,
    private val repository: CalibrationRepository,
    private val settingsRepository: SettingsRepository,
    private val engineVersion: String,
    private val clock: () -> Long,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val probeTokenSource: () -> Long = ::nextCalibrationProbeToken,
) {
    suspend fun runQuickCalibration(allowUnknownPower: Boolean = false): CalibrationRunResult {
        val snapshot = try {
            snapshotSource()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return CalibrationRunResult.Failed
        }
        when (snapshot.resources.thermalState) {
            ThermalState.SERIOUS,
            ThermalState.CRITICAL,
            -> return CalibrationRunResult.Deferred(CalibrationDeferralReason.THERMAL)
            else -> Unit
        }
        if (snapshot.resources.powerPolicyState == PowerPolicyState.POWER_SAVER) {
            return CalibrationRunResult.Deferred(CalibrationDeferralReason.POWER_SAVER)
        }
        if (snapshot.resources.powerPolicyState == PowerPolicyState.UNKNOWN && !allowUnknownPower) {
            return CalibrationRunResult.RequiresConfirmation
        }
        if (snapshot.resources.lowMemory == true) {
            return CalibrationRunResult.Deferred(CalibrationDeferralReason.LOW_MEMORY)
        }
        val backend = selectBackend(snapshot)
            ?: return CalibrationRunResult.Deferred(CalibrationDeferralReason.BACKEND_UNAVAILABLE)
        val bufferBytes = availableBudget(snapshot, backend)
            ?.takeIf { it >= MIN_BUFFER_BYTES }
            ?.coerceAtMost(MAX_BUFFER_BYTES)
            ?: return CalibrationRunResult.Deferred(CalibrationDeferralReason.INSUFFICIENT_MEMORY)
        val nativeBackend = backend.toNativeBackend()
        val probeToken = probeTokenSource().takeIf { it > 0L } ?: return CalibrationRunResult.Failed
        val nativeResult = try {
            withTimeoutOrNull(timeoutMillis) {
                probe.run(probeToken, nativeBackend, TARGET_DURATION_MILLIS, bufferBytes)
            } ?: run {
                probe.cancel(probeToken)
                return CalibrationRunResult.TimedOut
            }
        } catch (cancelled: CancellationException) {
            probe.cancel(probeToken)
            throw cancelled
        } catch (_: Exception) {
            return CalibrationRunResult.Failed
        }
        return when (nativeResult) {
            is BackendCalibrationResult.Complete -> persistComplete(
                requestedBackend = backend,
                expectedNativeBackend = nativeBackend,
                result = nativeResult,
            )
            BackendCalibrationResult.Cancelled -> CalibrationRunResult.Cancelled
            BackendCalibrationResult.Deferred ->
                CalibrationRunResult.Deferred(CalibrationDeferralReason.BACKEND_UNAVAILABLE)
            BackendCalibrationResult.Invalid,
            BackendCalibrationResult.Unavailable,
            BackendCalibrationResult.Failed,
            -> CalibrationRunResult.Failed
        }
    }

    suspend fun skipQuickCalibration() {
        settingsRepository.completeRecommendationCalibrationOffer()
    }

    private suspend fun persistComplete(
        requestedBackend: BackendKind,
        expectedNativeBackend: NativeBackendKind,
        result: BackendCalibrationResult.Complete,
    ): CalibrationRunResult {
        if (result.backend != expectedNativeBackend || result.windows.size !in MIN_TOTAL_WINDOWS..MAX_WINDOWS ||
            result.windows.count { it.metric == BackendCalibrationMetric.MEMORY_BANDWIDTH } < MIN_WINDOWS_PER_METRIC ||
            result.windows.count { it.metric == BackendCalibrationMetric.COMPUTE } < MIN_WINDOWS_PER_METRIC
        ) {
            return CalibrationRunResult.Failed
        }
        val capturedAt = clock().takeIf { it >= 0L } ?: return CalibrationRunResult.Failed
        val observations = buildList(result.windows.size) {
            result.windows.forEach { window ->
                val rate = rate(window.completedUnits, window.elapsedNanoseconds)
                    ?: return CalibrationRunResult.Failed
                val kind = when (window.metric) {
                    BackendCalibrationMetric.MEMORY_BANDWIDTH -> MetricKind.BANDWIDTH
                    BackendCalibrationMetric.COMPUTE -> MetricKind.COMPUTE
                }
                add(calibrationSample(requestedBackend, kind, rate, window.completedUnits, window.elapsedNanoseconds, capturedAt))
            }
        }
        if (!repository.recordAll(observations)) return CalibrationRunResult.Failed
        return try {
            settingsRepository.completeRecommendationCalibrationOffer()
            CalibrationRunResult.Completed(requestedBackend)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            CalibrationRunResult.Failed
        }
    }

    private fun calibrationSample(
        backend: BackendKind,
        metricKind: MetricKind,
        value: Double,
        completedUnits: Long,
        elapsedNanoseconds: Long,
        capturedAt: Long,
    ): RecommendationObservationEntity = RecommendationObservationEntity.from(
        key = CalibrationKey(
            backend = backend,
            architectureFamily = SYNTHETIC_ARCHITECTURE,
            quantizationFamily = SYNTHETIC_QUANTIZATION,
            workloadBucket = QUICK_WORKLOAD_BUCKET,
            engineVersion = engineVersion,
            metricKind = metricKind,
        ),
        predictedValue = value,
        observedValue = value,
        completedUnits = completedUnits,
        elapsedNanoseconds = elapsedNanoseconds,
        outcome = ObservationOutcome.SUCCESS,
        capturedAtEpochMs = capturedAt,
    )

    private fun rate(count: Long, elapsedNanoseconds: Long): Double? {
        if (count <= 0L || elapsedNanoseconds <= 0L) return null
        return (count.toDouble() * NANOS_PER_SECOND / elapsedNanoseconds.toDouble())
            .takeIf { it.isFinite() && it in MIN_RATE..MAX_RATE }
    }

    private fun selectBackend(snapshot: DeviceSnapshot): BackendKind? = BACKEND_PRIORITY.firstOrNull { kind ->
        snapshot.hardwareProfile.backends.any { it.kind == kind && it.status == BackendStatus.AVAILABLE }
    }

    private fun availableBudget(snapshot: DeviceSnapshot, backend: BackendKind): Long? = when (backend) {
        BackendKind.CPU -> snapshot.baseSharedBudgetBytes ?: snapshot.baseHostBudgetBytes
        BackendKind.METAL,
        BackendKind.VULKAN,
        BackendKind.CUDA,
        BackendKind.OTHER,
        -> snapshot.baseSharedBudgetBytes ?: snapshot.baseGpuBudgetBytes
    }

    private fun BackendKind.toNativeBackend(): NativeBackendKind = when (this) {
        BackendKind.CPU -> NativeBackendKind.CPU
        BackendKind.METAL -> NativeBackendKind.METAL
        BackendKind.VULKAN -> NativeBackendKind.VULKAN
        BackendKind.CUDA -> NativeBackendKind.CUDA
        BackendKind.OTHER -> NativeBackendKind.OTHER
    }

    private companion object {
        const val TARGET_DURATION_MILLIS = 3_000
        const val DEFAULT_TIMEOUT_MILLIS = 4_000L
        const val MIN_BUFFER_BYTES = 4L shl 20
        const val MAX_BUFFER_BYTES = 64L shl 20
        const val MIN_WINDOWS_PER_METRIC = 5
        const val MIN_TOTAL_WINDOWS = MIN_WINDOWS_PER_METRIC * 2
        const val MAX_WINDOWS = 16
        const val MIN_RATE = 1.0
        const val MAX_RATE = 1.0e18
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val SYNTHETIC_ARCHITECTURE = "synthetic"
        const val SYNTHETIC_QUANTIZATION = "none"
        const val QUICK_WORKLOAD_BUCKET = "quick-v1"
        val BACKEND_PRIORITY = listOf(
            BackendKind.METAL,
            BackendKind.CUDA,
            BackendKind.VULKAN,
            BackendKind.CPU,
        )
    }
}

@OptIn(ExperimentalAtomicApi::class)
private val calibrationProbeTokens = AtomicLong(0L)

@OptIn(ExperimentalAtomicApi::class)
private fun nextCalibrationProbeToken(): Long = calibrationProbeTokens.fetchAndAdd(1L) + 1L
