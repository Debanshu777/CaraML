package com.debanshu777.caraml.core.recommendation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BackendPerformanceTailTest {
    @Test
    fun conservativeThroughputKeepsDurationTailInTheEstimatedRange() {
        val profile = BackendPerformanceProfile(
            sustainedBytesPerSecond = GIB.toDouble(),
            sustainedOperationsPerSecond = 1.0e12,
            conservativeBytesPerSecond = GIB.toDouble() / 2.0,
            conservativeOperationsPerSecond = 0.5e12,
            confidence = Confidence.HIGH,
        )
        val source = object : CalibrationSource {
            override fun engineVersion() = "native-engine-v1"
            override fun backendProfileFor(backend: com.debanshu777.caraml.core.platform.BackendKind) = profile
            override fun correctionFor(key: CalibrationKey): CalibrationCorrection? = null
            override fun revision() = 0L
        }
        val result = PerformanceEstimator().estimate(
            descriptor = task6LlmDescriptor(sizeBytes = GIB, parameterCount = 1_000_000_000L),
            plan = task6LlmPlan(),
            hardware = task6Hardware(),
            calibration = source,
        )

        val estimate = assertIs<PerformanceEstimate.Llm>(result)
        assertEquals(0.5, estimate.decodeTokensPerSecond.low, 0.000_001)
        assertEquals(1.0, estimate.decodeTokensPerSecond.likely, 0.000_001)
        assertEquals(2.0, estimate.loadTimeSeconds.high, 0.000_001)
    }

    private companion object {
        const val GIB = 1_073_741_824L
    }
}
