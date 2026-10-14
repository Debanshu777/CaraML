package com.debanshu777.runner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BackendCalibrationResultTest {
    @Test
    fun completePayloadRequiresFiveFinitePositiveBoundedWindows() {
        val payload = longArrayOf(
            0, NativeBackendKind.CPU.ordinal.toLong(), 10,
            BackendCalibrationMetric.MEMORY_BANDWIDTH.ordinal.toLong(), 1_000, 100,
            BackendCalibrationMetric.MEMORY_BANDWIDTH.ordinal.toLong(), 1_000, 110,
            BackendCalibrationMetric.MEMORY_BANDWIDTH.ordinal.toLong(), 1_000, 120,
            BackendCalibrationMetric.MEMORY_BANDWIDTH.ordinal.toLong(), 1_000, 130,
            BackendCalibrationMetric.MEMORY_BANDWIDTH.ordinal.toLong(), 1_000, 140,
            BackendCalibrationMetric.COMPUTE.ordinal.toLong(), 2_000, 100,
            BackendCalibrationMetric.COMPUTE.ordinal.toLong(), 2_000, 110,
            BackendCalibrationMetric.COMPUTE.ordinal.toLong(), 2_000, 120,
            BackendCalibrationMetric.COMPUTE.ordinal.toLong(), 2_000, 130,
            BackendCalibrationMetric.COMPUTE.ordinal.toLong(), 2_000, 140,
        )

        val result = decodeBackendCalibrationResult(payload)

        assertIs<BackendCalibrationResult.Complete>(result)
        assertEquals(10, result.windows.size)
        assertEquals(5, result.windows.count { it.metric == BackendCalibrationMetric.MEMORY_BANDWIDTH })
        assertEquals(5, result.windows.count { it.metric == BackendCalibrationMetric.COMPUTE })
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
        assertEquals(false, isValidBackendCalibrationRequest(0L, 500, 4L shl 20))
        assertEquals(false, isValidBackendCalibrationRequest(1L, 499, 4L shl 20))
        assertEquals(false, isValidBackendCalibrationRequest(1L, 3_001, 4L shl 20))
        assertEquals(false, isValidBackendCalibrationRequest(1L, 500, (4L shl 20) - 1))
        assertEquals(false, isValidBackendCalibrationRequest(1L, 500, (64L shl 20) + 1))
        assertEquals(true, isValidBackendCalibrationRequest(1L, 3_000, 64L shl 20))
    }

    @Test
    fun reservationAndResultExposeQuarantineWithoutCollapsingItIntoUnavailable() {
        assertEquals(
            BackendCalibrationReservation.QUARANTINED,
            decodeBackendCalibrationReservation(2),
        )
        assertEquals(
            BackendCalibrationResult.Quarantined,
            decodeBackendCalibrationResult(longArrayOf(6, 0, 0)),
        )
    }

    @Test
    fun abandonmentOutcomeRejectsInvalidAndUnknownNativeValues() {
        assertEquals(
            BackendCalibrationAbandonment.QUARANTINED,
            decodeBackendCalibrationAbandonment(0),
        )
        assertEquals(
            BackendCalibrationAbandonment.NOT_ACTIVE,
            decodeBackendCalibrationAbandonment(1),
        )
        assertEquals(
            BackendCalibrationAbandonment.INVALID,
            decodeBackendCalibrationAbandonment(2),
        )
        assertEquals(
            BackendCalibrationAbandonment.UNAVAILABLE,
            decodeBackendCalibrationAbandonment(-1),
        )
        assertEquals(
            BackendCalibrationAbandonment.UNAVAILABLE,
            decodeBackendCalibrationAbandonment(Int.MAX_VALUE),
        )
    }
}
