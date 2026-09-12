package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.platform.BackendKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RawMemoryObservationEstimateTest {
    @Test
    fun calibratedLlmFootprintPreservesRawLoadAndGenerationBaselines() {
        val descriptor = llmDescriptor(
            sizeBytes = GIB,
            contextLimit = 131_072,
            architecture = "llama",
            shape = TransformerShape(32, 8, 32, 4_096, 128),
        )
        val plan = task6LlmPlan(backend = BackendKind.CPU)
        val estimator = LlmFootprintEstimator()
        val raw = estimator.estimate(descriptor, plan, MemoryCalibration.None)
        val calibrated = estimator.estimate(descriptor, plan, doubleCalibration())

        assertEquals(raw.rawMemoryByPhase, calibrated.rawMemoryByPhase)
        val load = assertNotNull(calibrated.rawMemoryByPhase.load.hostMemoryBytes)
        val generation = assertNotNull(calibrated.rawMemoryByPhase.generation.hostMemoryBytes)
        assertTrue(generation.likelyBytes > 0L)
        assertTrue(assertNotNull(calibrated.hostMemoryBytes).likelyBytes > load.likelyBytes + generation.likelyBytes)
    }

    @Test
    fun diffusionLoadExcludesActivationPeakAndGenerationIncludesIt() {
        val descriptor = task6DiffusionDescriptor()
        val fixture = task6DiffusionPlan()
        val plan = DiffusionRunPlan(
            mode = fixture.mode,
            width = fixture.width,
            height = fixture.height,
            frameCount = fixture.frameCount,
            batchSize = fixture.batchSize,
            steps = fixture.steps,
            vaeTiling = fixture.vaeTiling,
            offloadToCpu = fixture.offloadToCpu,
            keepClipOnCpu = fixture.keepClipOnCpu,
            keepVaeOnCpu = fixture.keepVaeOnCpu,
            maxVramBytes = fixture.maxVramBytes,
            layerStreaming = fixture.layerStreaming,
            requiresUserAcceptance = fixture.requiresUserAcceptance,
            backend = BackendKind.CPU,
            memoryTopology = com.debanshu777.caraml.core.platform.MemoryTopology.UNKNOWN,
            compromises = fixture.compromises,
        )
        val estimator = DiffusionFootprintEstimator()
        val raw = estimator.estimate(descriptor, plan, MemoryCalibration.None)
        val calibrated = estimator.estimate(descriptor, plan, doubleCalibration())

        assertEquals(raw.rawMemoryByPhase, calibrated.rawMemoryByPhase)
        val load = assertNotNull(calibrated.rawMemoryByPhase.load.hostMemoryBytes)
        val generation = assertNotNull(calibrated.rawMemoryByPhase.generation.hostMemoryBytes)
        assertTrue(generation.likelyBytes > 0L)
        assertEquals(descriptor.components.sumOf { it.file.sizeBytes } + 128L * MIB, load.likelyBytes)
    }

    private fun doubleCalibration() = MemoryCalibration.Correction(
        likelyNumerator = 2,
        likelyDenominator = 1,
        highNumerator = 2,
        highDenominator = 1,
    )

    private companion object {
        const val MIB = 1_048_576L
        const val GIB = 1_073_741_824L
    }
}
