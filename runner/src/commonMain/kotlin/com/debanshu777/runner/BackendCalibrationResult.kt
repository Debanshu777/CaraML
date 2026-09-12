package com.debanshu777.runner

enum class BackendCalibrationStatus {
    COMPLETE,
    CANCELLED,
    DEFERRED,
    INVALID,
    UNAVAILABLE,
    FAILED,
}

data class BackendCalibrationWindow(
    val bytesMoved: Long,
    val operations: Long,
    val elapsedNanoseconds: Long,
)

sealed interface BackendCalibrationResult {
    data class Complete(
        val backend: NativeBackendKind,
        val windows: List<BackendCalibrationWindow>,
    ) : BackendCalibrationResult

    data object Cancelled : BackendCalibrationResult
    data object Deferred : BackendCalibrationResult
    data object Invalid : BackendCalibrationResult
    data object Unavailable : BackendCalibrationResult
    data object Failed : BackendCalibrationResult
}

internal fun decodeBackendCalibrationResult(payload: LongArray?): BackendCalibrationResult {
    if (payload == null || payload.size < HEADER_FIELDS) return BackendCalibrationResult.Unavailable
    val status = payload[0].toIndex(BackendCalibrationStatus.entries.size)
        ?.let(BackendCalibrationStatus.entries::get) ?: return BackendCalibrationResult.Unavailable
    if (status != BackendCalibrationStatus.COMPLETE) {
        return when (status) {
            BackendCalibrationStatus.CANCELLED -> BackendCalibrationResult.Cancelled
            BackendCalibrationStatus.DEFERRED -> BackendCalibrationResult.Deferred
            BackendCalibrationStatus.INVALID -> BackendCalibrationResult.Invalid
            BackendCalibrationStatus.UNAVAILABLE -> BackendCalibrationResult.Unavailable
            BackendCalibrationStatus.FAILED -> BackendCalibrationResult.Failed
            BackendCalibrationStatus.COMPLETE -> error("handled above")
        }
    }
    val backend = payload[1].toIndex(NativeBackendKind.entries.size)
        ?.let(NativeBackendKind.entries::get) ?: return BackendCalibrationResult.Unavailable
    val count = payload[2].takeIf { it in MIN_WINDOWS.toLong()..MAX_WINDOWS.toLong() }?.toInt()
        ?: return BackendCalibrationResult.Unavailable
    if (payload.size != HEADER_FIELDS + count * WINDOW_FIELDS) return BackendCalibrationResult.Unavailable
    val windows = ArrayList<BackendCalibrationWindow>(count)
    repeat(count) { index ->
        val offset = HEADER_FIELDS + index * WINDOW_FIELDS
        val bytes = payload[offset]
        val operations = payload[offset + 1]
        val elapsed = payload[offset + 2]
        if (bytes !in 1..MAX_COUNTER || operations !in 1..MAX_COUNTER || elapsed !in 1..MAX_ELAPSED_NANOS) {
            return BackendCalibrationResult.Unavailable
        }
        windows += BackendCalibrationWindow(bytes, operations, elapsed)
    }
    return BackendCalibrationResult.Complete(backend, windows)
}

internal fun isValidBackendCalibrationRequest(durationMillis: Int, bufferBytes: Long): Boolean =
    durationMillis in MIN_DURATION_MS..MAX_DURATION_MS && bufferBytes in MIN_BUFFER_BYTES..MAX_BUFFER_BYTES

internal const val MIN_DURATION_MS = 500
internal const val MAX_DURATION_MS = 3_000
internal const val MIN_BUFFER_BYTES = 4L shl 20
internal const val MAX_BUFFER_BYTES = 64L shl 20
internal const val MIN_CALIBRATION_WINDOWS = 5
private const val MIN_WINDOWS = MIN_CALIBRATION_WINDOWS
private const val MAX_WINDOWS = 16
private const val HEADER_FIELDS = 3
private const val WINDOW_FIELDS = 3
private const val MAX_COUNTER = Long.MAX_VALUE / 4L
private const val MAX_ELAPSED_NANOS = 10_000_000_000L

private fun Long.toIndex(size: Int): Int? = takeIf { it in 0 until size.toLong() }?.toInt()
