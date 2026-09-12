package com.debanshu777.runner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BackendCalibrationResultTest {
    @Test
    fun completePayloadRequiresFiveFinitePositiveBoundedWindows() {
        val payload = longArrayOf(
            0, NativeBackendKind.CPU.ordinal.toLong(), 5,
            1_000, 2_000, 100,
            1_000, 2_000, 110,
            1_000, 2_000, 120,
            1_000, 2_000, 130,
            1_000, 2_000, 140,
        )

        val result = decodeBackendCalibrationResult(payload)

        assertIs<BackendCalibrationResult.Complete>(result)
        assertEquals(5, result.windows.size)
        assertEquals(NativeBackendKind.CPU, result.backend)
    }

    @Test
    fun malformedOrOversizedPayloadFailsClosed() {
        assertIs<BackendCalibrationResult.Unavailable>(decodeBackendCalibrationResult(longArrayOf(0, 0, 4)))
        assertIs<BackendCalibrationResult.Unavailable>(decodeBackendCalibrationResult(longArrayOf(0, 99, 5)))
        assertIs<BackendCalibrationResult.Unavailable>(
            decodeBackendCalibrationResult(longArrayOf(0, 0, 5, -1, 1, 1, 1, 1, 1)),
        )
    }

    @Test
    fun cancelledPayloadNeverExposesPartialWindows() {
        assertEquals(
            BackendCalibrationResult.Cancelled,
            decodeBackendCalibrationResult(longArrayOf(1, 0, 2, 100, 100, 100, 100, 100, 100)),
        )
    }

    @Test
    fun requestBoundsRejectUnboundedDurationAndBuffers() {
        assertEquals(false, isValidBackendCalibrationRequest(499, 4L shl 20))
        assertEquals(false, isValidBackendCalibrationRequest(3_001, 4L shl 20))
        assertEquals(false, isValidBackendCalibrationRequest(500, (4L shl 20) - 1))
        assertEquals(false, isValidBackendCalibrationRequest(500, (64L shl 20) + 1))
        assertEquals(true, isValidBackendCalibrationRequest(3_000, 64L shl 20))
    }
}
