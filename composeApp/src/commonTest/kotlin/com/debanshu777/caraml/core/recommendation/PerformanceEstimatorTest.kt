package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.MemoryTopology
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PerformanceEstimatorTest {
    private val estimator = PerformanceEstimator()

    @Test
    fun missingCalibrationReturnsHonestUnknownInsteadOfUsingCoreCount() {
        val estimate = estimator.estimate(
            descriptor = task6LlmDescriptor(),
            plan = task6LlmPlan(),
            hardware = task6Hardware(logicalCores = 128, performanceCores = 128),
            calibration = NoCalibrationSource,
        )

        val unknown = assertIs<PerformanceEstimate.Unknown>(estimate)
        assertEquals(AssessmentReason.SPEED_NOT_VERIFIED, unknown.reason)
    }

    @Test
    fun acceleratedLlmWithUnknownTopologyIsInvalidPerformanceEvidence() {
        val result = estimator.estimate(
            descriptor = task6LlmDescriptor(),
            plan = task6LlmPlan(
                backend = BackendKind.CUDA,
                topology = MemoryTopology.UNKNOWN,
                gpuLayers = 16,
            ),
            hardware = task6Hardware(backend = BackendKind.CUDA),
            calibration = FixedCalibrationSource(
                profile = BackendPerformanceProfile(GIB.toDouble(), 1.0e12, Confidence.HIGH),
            ),
        )

        assertEquals(
            AssessmentReason.INVALID_PERFORMANCE_EVIDENCE,
            assertIs<PerformanceEstimate.Unknown>(result).reason,
        )
    }

    @Test
    fun llmRooflineUsesBackendBandwidthComputeAndCalibrationCorrection() {
        val estimate = estimator.estimate(
            descriptor = task6LlmDescriptor(sizeBytes = GIB, parameterCount = 1_000_000_000L),
            plan = task6LlmPlan(),
            hardware = task6Hardware(),
            calibration = FixedCalibrationSource(
                profile = BackendPerformanceProfile(
                    sustainedBytesPerSecond = GIB.toDouble(),
                    sustainedOperationsPerSecond = 1_000_000_000_000.0,
                    confidence = Confidence.HIGH,
                    evidence = listOf(Evidence(AssessmentReason.PERFORMANCE_CALIBRATED, Confidence.HIGH)),
                ),
                correction = CalibrationCorrection(likely = 1.25, high = 2.0, confidence = Confidence.MEDIUM),
            ),
        )

        val llm = assertIs<PerformanceEstimate.Llm>(estimate)
        assertEquals(0.5, llm.decodeTokensPerSecond.low, 0.000_001)
        assertEquals(0.8, llm.decodeTokensPerSecond.likely, 0.000_001)
        assertEquals(1.0, llm.decodeTokensPerSecond.high, 0.000_001)
        assertEquals(Confidence.MEDIUM, llm.decodeTokensPerSecond.confidence)
        assertTrue(llm.decodeTokensPerSecond.evidence.any { it.reason == AssessmentReason.PERFORMANCE_ESTIMATED })
        assertTrue(llm.timeToFirstTokenSeconds.likely > llm.loadTimeSeconds.likely)
    }

    @Test
    fun invalidBackendAndCorrectionNumbersReturnStructuredUnknowns() {
        val invalidProfiles = listOf(
            BackendPerformanceProfile(Double.NaN, 1.0, Confidence.HIGH),
            BackendPerformanceProfile(Double.POSITIVE_INFINITY, 1.0, Confidence.HIGH),
            BackendPerformanceProfile(0.0, 1.0, Confidence.HIGH),
            BackendPerformanceProfile(1.0, -1.0, Confidence.HIGH),
        )
        invalidProfiles.forEach { profile ->
            val result = estimator.estimate(
                task6LlmDescriptor(),
                task6LlmPlan(),
                task6Hardware(),
                FixedCalibrationSource(profile = profile),
            )
            assertEquals(
                AssessmentReason.INVALID_PERFORMANCE_EVIDENCE,
                assertIs<PerformanceEstimate.Unknown>(result).reason,
            )
        }

        val invalidCorrection = estimator.estimate(
            task6LlmDescriptor(),
            task6LlmPlan(),
            task6Hardware(),
            FixedCalibrationSource(
                profile = BackendPerformanceProfile(GIB.toDouble(), 1.0e12, Confidence.HIGH),
                correction = CalibrationCorrection(Double.NaN, 2.0, Confidence.HIGH),
            ),
        )
        assertEquals(
            AssessmentReason.INVALID_PERFORMANCE_EVIDENCE,
            assertIs<PerformanceEstimate.Unknown>(invalidCorrection).reason,
        )
    }

    @Test
    fun diffusionImageReportsActualWorkloadAndCheckedReferenceDuration() {
        val estimate = estimator.estimate(
            descriptor = task6DiffusionDescriptor(),
            plan = task6DiffusionPlan(width = 1_024, height = 512, steps = 40),
            hardware = task6Hardware(backend = BackendKind.METAL, topology = MemoryTopology.UNIFIED),
            calibration = FixedCalibrationSource(
                profile = BackendPerformanceProfile(
                    sustainedBytesPerSecond = (4L * GIB).toDouble(),
                    sustainedOperationsPerSecond = 1.0e15,
                    confidence = Confidence.HIGH,
                ),
            ),
        )

        val image = assertIs<PerformanceEstimate.DiffusionImage>(estimate)
        assertTrue(image.totalTimeSeconds.likely > image.secondsPerStep.likely)
        assertEquals(
            image.totalTimeSeconds.likely / 4.0,
            image.referenceTotalTimeSeconds.likely,
            0.000_001,
        )
    }

    @Test
    fun diffusionVideoReportsFrameTimingButMarksItNonComparable() {
        val result = estimator.estimate(
            descriptor = task6DiffusionDescriptor(mode = DiffusionMode.VIDEO),
            plan = task6DiffusionPlan(mode = DiffusionMode.VIDEO, frames = 8),
            hardware = task6Hardware(backend = BackendKind.METAL, topology = MemoryTopology.UNIFIED),
            calibration = FixedCalibrationSource(
                profile = BackendPerformanceProfile(GIB.toDouble(), 1.0e15, Confidence.MEDIUM),
            ),
        )

        val video = assertIs<PerformanceEstimate.DiffusionVideo>(result)
        assertEquals(false, video.comparableForPolicy)
        assertTrue(video.totalTimeSeconds.likely > video.secondsPerFrame.likely)
    }

    @Test
    fun unrepresentablePredictionReturnsInvalidEvidenceWithoutInfinity() {
        val result = estimator.estimate(
            descriptor = task6LlmDescriptor(sizeBytes = DescriptorLimits.MAX_FILE_BYTES),
            plan = task6LlmPlan(),
            hardware = task6Hardware(),
            calibration = FixedCalibrationSource(
                profile = BackendPerformanceProfile(
                    sustainedBytesPerSecond = Double.MIN_VALUE,
                    sustainedOperationsPerSecond = Double.MIN_VALUE,
                    confidence = Confidence.HIGH,
                ),
            ),
        )

        val unknown = assertIs<PerformanceEstimate.Unknown>(result)
        assertEquals(AssessmentReason.ARITHMETIC_OVERFLOW, unknown.reason)
        assertTrue(unknown.evidence.none { it.detail?.contains("Infinity") == true })
    }

    @Test
    fun malformedPlanInputsAreRejectedBeforeRooflineArithmetic() {
        val result = estimator.estimate(
            descriptor = task6LlmDescriptor(),
            plan = task6LlmPlan(keyContext = -1),
            hardware = task6Hardware(),
            calibration = FixedCalibrationSource(
                profile = BackendPerformanceProfile(GIB.toDouble(), 1.0e12, Confidence.HIGH),
            ),
        )

        assertEquals(
            AssessmentReason.INVALID_PERFORMANCE_EVIDENCE,
            assertIs<PerformanceEstimate.Unknown>(result).reason,
        )
    }

    @Test
    fun missingOrInvalidEngineVersionPreventsCalibrationLookup() {
        val profile = BackendPerformanceProfile(GIB.toDouble(), 1.0e12, Confidence.HIGH)
        val missing = estimator.estimate(
            task6LlmDescriptor(),
            task6LlmPlan(),
            task6Hardware(),
            FixedCalibrationSource(profile = profile, engineVersion = null),
        )
        val invalid = estimator.estimate(
            task6LlmDescriptor(),
            task6LlmPlan(),
            task6Hardware(),
            FixedCalibrationSource(profile = profile, engineVersion = "runner version with spaces"),
        )

        assertEquals(AssessmentReason.SPEED_NOT_VERIFIED, assertIs<PerformanceEstimate.Unknown>(missing).reason)
        assertEquals(
            AssessmentReason.INVALID_PERFORMANCE_EVIDENCE,
            assertIs<PerformanceEstimate.Unknown>(invalid).reason,
        )
    }

    @Test
    fun calibrationKeysAndCorrectionsArePartitionedByEngineVersion() {
        val profile = BackendPerformanceProfile(GIB.toDouble(), 1.0e12, Confidence.HIGH)
        val versionOne = FixedCalibrationSource(
            profile = profile,
            engineVersion = "llama-1.0.0",
            correction = CalibrationCorrection(likely = 2.0, high = 2.0, confidence = Confidence.HIGH),
            correctionEngineVersion = "llama-1.0.0",
        )
        val versionTwo = FixedCalibrationSource(
            profile = profile,
            engineVersion = "llama-2.0.0",
            correction = CalibrationCorrection(likely = 2.0, high = 2.0, confidence = Confidence.HIGH),
            correctionEngineVersion = "llama-1.0.0",
        )

        val first = assertIs<PerformanceEstimate.Llm>(
            estimator.estimate(task6LlmDescriptor(sizeBytes = GIB), task6LlmPlan(), task6Hardware(), versionOne),
        )
        val second = assertIs<PerformanceEstimate.Llm>(
            estimator.estimate(task6LlmDescriptor(sizeBytes = GIB), task6LlmPlan(), task6Hardware(), versionTwo),
        )

        assertEquals("llama-1.0.0", versionOne.seenKeys.single().engineVersion)
        assertEquals("llama-2.0.0", versionTwo.seenKeys.single().engineVersion)
        assertTrue(versionOne.seenKeys.single() != versionTwo.seenKeys.single())
        assertEquals(0.5, first.decodeTokensPerSecond.likely, 0.000_001)
        assertEquals(1.0, second.decodeTokensPerSecond.likely, 0.000_001)
    }

    private class FixedCalibrationSource(
        private val profile: BackendPerformanceProfile?,
        private val correction: CalibrationCorrection? = null,
        private val engineVersion: String? = "runner-1.0.0",
        private val correctionEngineVersion: String? = engineVersion,
    ) : CalibrationSource {
        val seenKeys = mutableListOf<CalibrationKey>()

        override fun engineVersion(): String? = engineVersion

        override fun backendProfileFor(backend: BackendKind): BackendPerformanceProfile? = profile

        override fun correctionFor(key: CalibrationKey): CalibrationCorrection? {
            seenKeys += key
            return correction.takeIf { key.engineVersion == correctionEngineVersion }
        }

        override fun revision(): Long = 7L
    }

    private companion object {
        const val GIB = 1_073_741_824L
    }
}
