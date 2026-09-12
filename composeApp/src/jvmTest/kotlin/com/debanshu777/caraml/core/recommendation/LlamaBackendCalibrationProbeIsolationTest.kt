package com.debanshu777.caraml.core.recommendation

import com.debanshu777.runner.BackendCalibrationResult
import com.debanshu777.runner.BackendCalibrationReservation
import com.debanshu777.runner.NativeBackendKind
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LlamaBackendCalibrationProbeIsolationTest {
    @Test
    fun timeoutReturnsWithoutWaitingForAStuckNativeCallAndCancelsOnlyItsActiveToken() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val cancelledTokens = mutableListOf<Long>()
        val reservedTokens = mutableListOf<Long>()
        val abandonedTokens = mutableListOf<Long>()
        val probe = LlamaBackendCalibrationProbe(
            dispatcher = Dispatchers.IO,
            calibrateBackend = { _, _, _, _ ->
                entered.countDown()
                try {
                    release.await(5, TimeUnit.SECONDS)
                    BackendCalibrationResult.Unavailable
                } finally {
                    completed.countDown()
                }
            },
            cancelBackendCalibration = { cancelledTokens += it },
            reserveBackendCalibration = {
                reservedTokens += it
                BackendCalibrationReservation.ACCEPTED
            },
            abandonBackendCalibration = { abandonedTokens += it },
        )

        try {
            var result: BackendCalibrationResult? = BackendCalibrationResult.Failed
            val elapsedMillis = measureTimeMillis {
                result = withTimeoutOrNull(500) {
                    probe.run(41L, NativeBackendKind.CPU, 3_000, 4L shl 20)
                }
            }

            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertNull(result)
            assertTrue(elapsedMillis < 1_500L, "timeout took $elapsedMillis ms")
            probe.cancel(99L)
            probe.abandon(99L)
            probe.abandon(41L)
            assertEquals(listOf(41L), reservedTokens)
            assertEquals(listOf(41L), cancelledTokens)
            assertEquals(listOf(41L), abandonedTokens)
        } finally {
            release.countDown()
            assertTrue(completed.await(1, TimeUnit.SECONDS))
        }
    }
}
