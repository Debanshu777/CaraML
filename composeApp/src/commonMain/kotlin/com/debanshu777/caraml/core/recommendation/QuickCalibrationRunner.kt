package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.data.settings.SettingsRepository
import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.BackendStatus
import com.debanshu777.caraml.core.platform.DeviceSnapshot
import com.debanshu777.caraml.core.platform.PlatformPaths
import com.debanshu777.caraml.core.platform.PowerPolicyState
import com.debanshu777.caraml.core.platform.ThermalState
import com.debanshu777.caraml.core.recommendation.storage.RecommendationObservationEntity
import com.debanshu777.runner.BackendCalibrationResult
import com.debanshu777.runner.LlamaRunner
import com.debanshu777.runner.NativeBackendKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

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
        backend: NativeBackendKind,
        durationMillis: Int,
        bufferBytes: Long,
    ): BackendCalibrationResult

    fun cancel()
}

class LlamaBackendCalibrationProbe(
    private val runner: LlamaRunner,
    private val dispatcher: CoroutineDispatcher,
    private val nativeLibraryDirectory: () -> String = PlatformPaths::getNativeLibDir,
) : BackendCalibrationProbe {
    override suspend fun run(
        backend: NativeBackendKind,
        durationMillis: Int,
        bufferBytes: Long,
    ): BackendCalibrationResult = coroutineScope {
        val directory = nativeLibraryDirectory()
        if (directory.isBlank() || directory.encodeToByteArray().size > MAX_NATIVE_DIRECTORY_BYTES || '\u0000' in directory) {
            return@coroutineScope BackendCalibrationResult.Unavailable
        }
        val started = CompletableDeferred<Unit>()
        val work = async(dispatcher) {
            runner.initialize(directory)
            started.complete(Unit)
            runner.calibrateBackend(backend, durationMillis, bufferBytes)
        }
        try {
            started.await()
            work.await()
        } catch (cancelled: CancellationException) {
            runner.cancelBackendCalibration()
            work.cancelAndJoin()
            throw cancelled
        } catch (_: Exception) {
            work.cancel()
            BackendCalibrationResult.Unavailable
        }
    }

    override fun cancel() = runner.cancelBackendCalibration()

    private companion object {
        const val MAX_NATIVE_DIRECTORY_BYTES = 4_096
    }
}

class QuickCalibrationRunner(
    private val snapshotSource: suspend () -> DeviceSnapshot,
    private val probe: BackendCalibrationProbe,
    private val repository: CalibrationRepository,
    private val settingsRepository: SettingsRepository,
    private val engineVersion: String,
    private val clock: () -> Long,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
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
        val nativeResult = try {
            withTimeoutOrNull(timeoutMillis) {
                probe.run(nativeBackend, TARGET_DURATION_MILLIS, bufferBytes)
            } ?: return CalibrationRunResult.TimedOut
        } catch (cancelled: CancellationException) {
            probe.cancel()
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
        if (result.backend != expectedNativeBackend || result.windows.size !in MIN_WINDOWS..MAX_WINDOWS) {
            return CalibrationRunResult.Failed
        }
        val capturedAt = clock().takeIf { it >= 0L } ?: return CalibrationRunResult.Failed
        val observations = buildList(result.windows.size * 2) {
            result.windows.forEach { window ->
                val bandwidth = rate(window.bytesMoved, window.elapsedNanoseconds)
                    ?: return CalibrationRunResult.Failed
                val compute = rate(window.operations, window.elapsedNanoseconds)
                    ?: return CalibrationRunResult.Failed
                add(calibrationSample(requestedBackend, MetricKind.BANDWIDTH, bandwidth, window.bytesMoved, window.elapsedNanoseconds, capturedAt))
                add(calibrationSample(requestedBackend, MetricKind.COMPUTE, compute, window.operations, window.elapsedNanoseconds, capturedAt))
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
        const val MIN_WINDOWS = 5
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
