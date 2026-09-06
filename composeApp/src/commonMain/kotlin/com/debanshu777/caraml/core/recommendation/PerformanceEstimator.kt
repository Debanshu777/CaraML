package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.BackendStatus
import com.debanshu777.caraml.core.platform.HardwareProfile
import kotlin.math.max

enum class MetricKind {
    PERFORMANCE,
    MEMORY,
}

data class CalibrationKey(
    val backend: BackendKind,
    val architectureFamily: String,
    val quantizationFamily: String,
    val workloadBucket: String,
    val engineVersion: String,
    val estimatorVersion: Int = PerformanceEstimator.VERSION,
    val metricKind: MetricKind = MetricKind.PERFORMANCE,
    val memoryPool: String? = null,
)

@ConsistentCopyVisibility
data class BackendPerformanceProfile private constructor(
    val sustainedBytesPerSecond: Double,
    val sustainedOperationsPerSecond: Double,
    val confidence: Confidence,
    val evidence: List<Evidence>,
) {
    constructor(
        sustainedBytesPerSecond: Double,
        sustainedOperationsPerSecond: Double,
        confidence: Confidence,
        evidence: Collection<Evidence> = emptyList(),
    ) : this(
        sustainedBytesPerSecond,
        sustainedOperationsPerSecond,
        confidence,
        evidence.toList(),
    )
}

data class CalibrationCorrection(
    val likely: Double,
    val high: Double,
    val confidence: Confidence = Confidence.MEDIUM,
)

interface CalibrationSource {
    fun engineVersion(): String?
    fun backendProfileFor(backend: BackendKind): BackendPerformanceProfile?
    fun correctionFor(key: CalibrationKey): CalibrationCorrection?
    fun revision(): Long
}

data object NoCalibrationSource : CalibrationSource {
    override fun engineVersion(): String? = null
    override fun backendProfileFor(backend: BackendKind): BackendPerformanceProfile? = null
    override fun correctionFor(key: CalibrationKey): CalibrationCorrection? = null
    override fun revision(): Long = 0L
}

@ConsistentCopyVisibility
data class PerformanceRange private constructor(
    val low: Double,
    val likely: Double,
    val high: Double,
    val confidence: Confidence,
    val evidence: List<Evidence>,
    internal val collectionLimitExceeded: Boolean,
) {
    companion object {
        fun create(
            low: Double,
            likely: Double,
            high: Double,
            confidence: Confidence,
            evidence: Collection<Evidence> = emptyList(),
        ): PerformanceRange? = if (
            low.isFinite() && likely.isFinite() && high.isFinite() &&
            low > 0.0 && low <= likely && likely <= high &&
            high <= RecommendationPolicyV1.MAX_SERIALIZED_RATE
        ) {
            boundedCollectionSnapshot(evidence, RecommendationPolicyV1.MAX_EVIDENCE_ENTRIES).let { snapshot ->
                PerformanceRange(low, likely, high, confidence, snapshot.values, snapshot.limitExceeded)
            }
        } else {
            null
        }
    }
}

sealed interface PerformanceEstimate {
    val evidence: List<Evidence>

    @ConsistentCopyVisibility
    data class Unknown private constructor(
        val reason: AssessmentReason,
        override val evidence: List<Evidence>,
        internal val collectionLimitExceeded: Boolean,
    ) : PerformanceEstimate {
        constructor(
            reason: AssessmentReason,
            evidence: Collection<Evidence> = listOf(Evidence(reason, Confidence.LOW)),
        ) : this(
            reason,
            boundedCollectionSnapshot(evidence, RecommendationPolicyV1.MAX_EVIDENCE_ENTRIES),
        )

        private constructor(
            reason: AssessmentReason,
            evidence: BoundedCollectionSnapshot<Evidence>,
        ) : this(reason, evidence.values, evidence.limitExceeded)
    }

    @ConsistentCopyVisibility
    data class Llm private constructor(
        val promptTokensPerSecond: PerformanceRange,
        val decodeTokensPerSecond: PerformanceRange,
        val timeToFirstTokenSeconds: PerformanceRange,
        val loadTimeSeconds: PerformanceRange,
        override val evidence: List<Evidence>,
        internal val collectionLimitExceeded: Boolean,
    ) : PerformanceEstimate {
        constructor(
            promptTokensPerSecond: PerformanceRange,
            decodeTokensPerSecond: PerformanceRange,
            timeToFirstTokenSeconds: PerformanceRange,
            loadTimeSeconds: PerformanceRange,
            evidence: Collection<Evidence>,
        ) : this(
            promptTokensPerSecond,
            decodeTokensPerSecond,
            timeToFirstTokenSeconds,
            loadTimeSeconds,
            boundedCollectionSnapshot(evidence, RecommendationPolicyV1.MAX_EVIDENCE_ENTRIES),
        )

        private constructor(
            promptTokensPerSecond: PerformanceRange,
            decodeTokensPerSecond: PerformanceRange,
            timeToFirstTokenSeconds: PerformanceRange,
            loadTimeSeconds: PerformanceRange,
            evidence: BoundedCollectionSnapshot<Evidence>,
        ) : this(
            promptTokensPerSecond,
            decodeTokensPerSecond,
            timeToFirstTokenSeconds,
            loadTimeSeconds,
            evidence.values,
            evidence.limitExceeded,
        )
    }

    @ConsistentCopyVisibility
    data class DiffusionImage private constructor(
        val secondsPerStep: PerformanceRange,
        val totalTimeSeconds: PerformanceRange,
        val referenceTotalTimeSeconds: PerformanceRange,
        override val evidence: List<Evidence>,
        internal val collectionLimitExceeded: Boolean,
    ) : PerformanceEstimate {
        constructor(
            secondsPerStep: PerformanceRange,
            totalTimeSeconds: PerformanceRange,
            referenceTotalTimeSeconds: PerformanceRange,
            evidence: Collection<Evidence>,
        ) : this(
            secondsPerStep,
            totalTimeSeconds,
            referenceTotalTimeSeconds,
            boundedCollectionSnapshot(evidence, RecommendationPolicyV1.MAX_EVIDENCE_ENTRIES),
        )

        private constructor(
            secondsPerStep: PerformanceRange,
            totalTimeSeconds: PerformanceRange,
            referenceTotalTimeSeconds: PerformanceRange,
            evidence: BoundedCollectionSnapshot<Evidence>,
        ) : this(
            secondsPerStep,
            totalTimeSeconds,
            referenceTotalTimeSeconds,
            evidence.values,
            evidence.limitExceeded,
        )
    }

    @ConsistentCopyVisibility
    data class DiffusionVideo private constructor(
        val secondsPerStep: PerformanceRange,
        val secondsPerFrame: PerformanceRange,
        val totalTimeSeconds: PerformanceRange,
        val comparableForPolicy: Boolean,
        override val evidence: List<Evidence>,
        internal val collectionLimitExceeded: Boolean,
    ) : PerformanceEstimate {
        constructor(
            secondsPerStep: PerformanceRange,
            secondsPerFrame: PerformanceRange,
            totalTimeSeconds: PerformanceRange,
            comparableForPolicy: Boolean,
            evidence: Collection<Evidence>,
        ) : this(
            secondsPerStep,
            secondsPerFrame,
            totalTimeSeconds,
            comparableForPolicy,
            boundedCollectionSnapshot(evidence, RecommendationPolicyV1.MAX_EVIDENCE_ENTRIES),
        )

        private constructor(
            secondsPerStep: PerformanceRange,
            secondsPerFrame: PerformanceRange,
            totalTimeSeconds: PerformanceRange,
            comparableForPolicy: Boolean,
            evidence: BoundedCollectionSnapshot<Evidence>,
        ) : this(
            secondsPerStep,
            secondsPerFrame,
            totalTimeSeconds,
            comparableForPolicy,
            evidence.values,
            evidence.limitExceeded,
        )
    }
}

class PerformanceEstimator {
    fun estimate(
        descriptor: ModelDescriptor,
        plan: RunPlan,
        hardware: HardwareProfile,
        calibration: CalibrationSource,
    ): PerformanceEstimate {
        if (validateRunPlan(plan) != null) {
            return unknown(AssessmentReason.INVALID_PERFORMANCE_EVIDENCE, "run-plan")
        }
        val backend = hardware.backends.firstOrNull { it.kind == plan.backend }
        if (backend == null || backend.status != BackendStatus.AVAILABLE) {
            return unknown(AssessmentReason.SPEED_NOT_VERIFIED, "backend-not-validated")
        }
        val engineVersion = calibration.engineVersion()
            ?: return unknown(AssessmentReason.SPEED_NOT_VERIFIED, "engine-version-unavailable")
        if (!isValidEngineVersion(engineVersion)) {
            return unknown(AssessmentReason.INVALID_PERFORMANCE_EVIDENCE, "engine-version-invalid")
        }
        val backendProfile = calibration.backendProfileFor(plan.backend)
            ?: return unknown(AssessmentReason.SPEED_NOT_VERIFIED, "calibration-unavailable")
        if (!validPositiveFinite(backendProfile.sustainedBytesPerSecond) ||
            !validPositiveFinite(backendProfile.sustainedOperationsPerSecond)
        ) {
            return unknown(AssessmentReason.INVALID_PERFORMANCE_EVIDENCE, "backend-profile")
        }

        val key = calibrationKey(descriptor, plan, engineVersion)
        val correction = calibration.correctionFor(key)
        if (correction != null && !validCorrection(correction)) {
            return unknown(AssessmentReason.INVALID_PERFORMANCE_EVIDENCE, "calibration-correction")
        }
        val likelyCorrection = correction?.likely ?: 1.0
        val highCorrection = correction?.high ?: 1.0
        val confidence = minimumConfidence(backendProfile.confidence, correction?.confidence ?: backendProfile.confidence)
        val factors = CorrectionFactors(
            low = minOf(1.0, likelyCorrection),
            likely = likelyCorrection,
            high = highCorrection,
        )
        val evidence = buildList {
            add(Evidence(AssessmentReason.PERFORMANCE_ESTIMATED, confidence, "roofline-v$VERSION:${plan.backend}"))
            addAll(backendProfile.evidence)
            if (correction != null) {
                add(Evidence(AssessmentReason.PERFORMANCE_CALIBRATED, confidence, "revision=${calibration.revision()}"))
            }
        }

        return when {
            descriptor is LlmModelDescriptor && plan is LlmRunPlan -> estimateLlm(
                descriptor,
                plan,
                backendProfile,
                factors,
                confidence,
                evidence,
            )
            descriptor is DiffusionModelDescriptor && plan is DiffusionRunPlan -> estimateDiffusion(
                descriptor,
                plan,
                backendProfile,
                factors,
                confidence,
                evidence,
            )
            else -> unknown(AssessmentReason.INVALID_PERFORMANCE_EVIDENCE, "descriptor-plan-mismatch")
        }
    }

    private fun estimateLlm(
        descriptor: LlmModelDescriptor,
        plan: LlmRunPlan,
        profile: BackendPerformanceProfile,
        factors: CorrectionFactors,
        confidence: Confidence,
        evidence: List<Evidence>,
    ): PerformanceEstimate {
        val bytes = (descriptor.checkedTotalFileBytes() as? CheckedLong.Value)?.value
        val parameters = descriptor.parameterCount
        if (bytes == null || bytes !in 1..DescriptorLimits.MAX_BUNDLE_BYTES ||
            parameters == null || parameters !in 1..DescriptorLimits.MAX_PARAMETERS
        ) {
            return unknown(AssessmentReason.SPEED_NOT_VERIFIED, "llm-compute-inputs")
        }
        val operations = parameters.toDouble() * 2.0
        if (!validPositiveFinite(operations)) {
            return unknown(AssessmentReason.ARITHMETIC_OVERFLOW, "llm-operations")
        }
        val secondsPerDecode = max(
            bytes.toDouble() / profile.sustainedBytesPerSecond,
            operations / profile.sustainedOperationsPerSecond,
        )
        val decodeDuration = durationRange(secondsPerDecode, factors, confidence, evidence)
            ?: return unknown(AssessmentReason.ARITHMETIC_OVERFLOW, "llm-decode-duration")
        val decodeRate = reciprocalRange(decodeDuration)
            ?: return unknown(AssessmentReason.ARITHMETIC_OVERFLOW, "llm-decode-rate")
        val promptBatchFactor = minOf(plan.batchSize, MAX_PROMPT_BATCH_FACTOR).toDouble()
        val promptRate = scaledRateRange(decodeRate, promptBatchFactor)
            ?: return unknown(AssessmentReason.ARITHMETIC_OVERFLOW, "llm-prompt-rate")
        val loadDuration = durationRange(
            bytes.toDouble() / profile.sustainedBytesPerSecond,
            factors,
            confidence,
            evidence,
        ) ?: return unknown(AssessmentReason.ARITHMETIC_OVERFLOW, "llm-load-duration")
        val promptTokens = minOf(plan.contextTokens, DEFAULT_PROMPT_REFERENCE_TOKENS).toDouble()
        val ttft = PerformanceRange.create(
            loadDuration.low + promptTokens / promptRate.high,
            loadDuration.likely + promptTokens / promptRate.likely,
            loadDuration.high + promptTokens / promptRate.low,
            confidence,
            evidence,
        )?.takeIf { it.high <= RecommendationPolicyV1.MAX_SERIALIZED_PERFORMANCE_VALUE }
            ?: return unknown(AssessmentReason.ARITHMETIC_OVERFLOW, "llm-ttft")

        return PerformanceEstimate.Llm(promptRate, decodeRate, ttft, loadDuration, evidence)
    }

    private fun estimateDiffusion(
        descriptor: DiffusionModelDescriptor,
        plan: DiffusionRunPlan,
        profile: BackendPerformanceProfile,
        factors: CorrectionFactors,
        confidence: Confidence,
        evidence: List<Evidence>,
    ): PerformanceEstimate {
        if (descriptor.components.isEmpty() || descriptor.components.size > DescriptorLimits.MAX_COMPONENTS) {
            return unknown(AssessmentReason.SPEED_NOT_VERIFIED, "diffusion-components")
        }
        var weightBytes = 0L
        descriptor.components.forEach { component ->
            weightBytes = when (val result = checkedAdd(weightBytes, component.file.sizeBytes)) {
                is CheckedLong.Invalid -> return unknown(result.reason, "diffusion-weight-bytes")
                is CheckedLong.Value -> result.value
            }
        }
        val pixelCount = checkedMultiply(plan.width.toLong(), plan.height.toLong())
        if (pixelCount is CheckedLong.Invalid || plan.steps <= 0 || plan.batchSize <= 0 || plan.frameCount <= 0) {
            return unknown(AssessmentReason.INVALID_PERFORMANCE_EVIDENCE, "diffusion-workload")
        }
        pixelCount as CheckedLong.Value
        val scale = pixelCount.value.toDouble() / REFERENCE_PIXELS.toDouble() * plan.batchSize.toDouble()
        val bytesPerStep = weightBytes.toDouble() * scale
        val operationsPerStep = weightBytes.toDouble() * 2.0 * scale
        if (!validPositiveFinite(bytesPerStep) || !validPositiveFinite(operationsPerStep)) {
            return unknown(AssessmentReason.ARITHMETIC_OVERFLOW, "diffusion-roofline-input")
        }
        val baseSecondsPerStep = max(
            bytesPerStep / profile.sustainedBytesPerSecond,
            operationsPerStep / profile.sustainedOperationsPerSecond,
        )
        val secondsPerStep = durationRange(baseSecondsPerStep, factors, confidence, evidence)
            ?: return unknown(AssessmentReason.ARITHMETIC_OVERFLOW, "diffusion-step-duration")
        val secondsPerFrame = scaleDurationRange(secondsPerStep, plan.steps.toDouble())
            ?: return unknown(AssessmentReason.ARITHMETIC_OVERFLOW, "diffusion-frame-duration")
        val total = scaleDurationRange(secondsPerFrame, plan.frameCount.toDouble())
            ?: return unknown(AssessmentReason.ARITHMETIC_OVERFLOW, "diffusion-total-duration")

        if (plan.mode == DiffusionMode.VIDEO) {
            return PerformanceEstimate.DiffusionVideo(
                secondsPerStep,
                secondsPerFrame,
                total,
                comparableForPolicy = false,
                evidence,
            )
        }
        val normalization = scale * (plan.steps.toDouble() / REFERENCE_STEPS.toDouble())
        if (!validPositiveFinite(normalization)) {
            return unknown(AssessmentReason.INVALID_PERFORMANCE_EVIDENCE, "diffusion-normalization")
        }
        val reference = divideDurationRange(total, normalization)
            ?: return unknown(AssessmentReason.ARITHMETIC_OVERFLOW, "diffusion-reference-duration")
        return PerformanceEstimate.DiffusionImage(secondsPerStep, total, reference, evidence)
    }

    private fun calibrationKey(
        descriptor: ModelDescriptor,
        plan: RunPlan,
        engineVersion: String,
    ): CalibrationKey = CalibrationKey(
        backend = plan.backend,
        architectureFamily = when (descriptor) {
            is LlmModelDescriptor -> descriptor.architecture ?: "unknown"
            is DiffusionModelDescriptor -> descriptor.architecture?.name ?: descriptor.family
        }.take(DescriptorLimits.MAX_METADATA_STRING_LENGTH),
        quantizationFamily = when (descriptor) {
            is LlmModelDescriptor -> when (val value = descriptor.quantization) {
                is QuantizationEvidence.Known -> value.quantization
                is QuantizationEvidence.Mixed -> "mixed"
                QuantizationEvidence.Unknown -> "unknown"
            }
            is DiffusionModelDescriptor -> descriptor.quantizationDistribution.sorted().joinToString("+")
        }.take(DescriptorLimits.MAX_METADATA_STRING_LENGTH),
        workloadBucket = when (plan) {
            is LlmRunPlan -> "ctx-${plan.contextTokens}"
            is DiffusionRunPlan -> "${plan.mode.name.lowercase()}-${plan.width}x${plan.height}-${plan.steps}"
        },
        engineVersion = engineVersion,
    )

    private fun durationRange(
        baseSeconds: Double,
        correction: CorrectionFactors,
        confidence: Confidence,
        evidence: Collection<Evidence>,
    ): PerformanceRange? {
        if (!validPositiveFinite(baseSeconds)) return null
        val low = baseSeconds * correction.low
        val likely = baseSeconds * correction.likely
        val high = baseSeconds * correction.high
        if (high > RecommendationPolicyV1.MAX_SERIALIZED_PERFORMANCE_VALUE) return null
        return PerformanceRange.create(
            low,
            likely,
            high,
            confidence,
            evidence,
        )
    }

    private fun reciprocalRange(duration: PerformanceRange): PerformanceRange? = PerformanceRange.create(
        low = minOf(1.0 / duration.high, RecommendationPolicyV1.MAX_SERIALIZED_RATE),
        likely = minOf(1.0 / duration.likely, RecommendationPolicyV1.MAX_SERIALIZED_RATE),
        high = minOf(1.0 / duration.low, RecommendationPolicyV1.MAX_SERIALIZED_RATE),
        confidence = duration.confidence,
        evidence = duration.evidence,
    )

    private fun scaledRateRange(range: PerformanceRange, factor: Double): PerformanceRange? {
        if (!validPositiveFinite(factor)) return null
        return PerformanceRange.create(
            minOf(range.low * factor, RecommendationPolicyV1.MAX_SERIALIZED_RATE),
            minOf(range.likely * factor, RecommendationPolicyV1.MAX_SERIALIZED_RATE),
            minOf(range.high * factor, RecommendationPolicyV1.MAX_SERIALIZED_RATE),
            range.confidence,
            range.evidence,
        )
    }

    private fun scaleDurationRange(range: PerformanceRange, factor: Double): PerformanceRange? {
        if (!validPositiveFinite(factor)) return null
        val high = range.high * factor
        if (!high.isFinite() || high > RecommendationPolicyV1.MAX_SERIALIZED_PERFORMANCE_VALUE) return null
        return PerformanceRange.create(
            range.low * factor,
            range.likely * factor,
            high,
            range.confidence,
            range.evidence,
        )
    }

    private fun divideDurationRange(range: PerformanceRange, divisor: Double): PerformanceRange? {
        if (!validPositiveFinite(divisor)) return null
        val high = range.high / divisor
        if (!high.isFinite() || high > RecommendationPolicyV1.MAX_SERIALIZED_PERFORMANCE_VALUE) return null
        return PerformanceRange.create(
            range.low / divisor,
            range.likely / divisor,
            high,
            range.confidence,
            range.evidence,
        )
    }

    private fun validCorrection(value: CalibrationCorrection): Boolean =
        validPositiveFinite(value.likely) && validPositiveFinite(value.high) && value.high >= value.likely

    private fun validPositiveFinite(value: Double): Boolean = value.isFinite() && value > 0.0

    private fun isValidEngineVersion(value: String): Boolean =
        value.length in 1..DescriptorLimits.MAX_METADATA_STRING_LENGTH && ENGINE_VERSION.matches(value)

    private fun minimumConfidence(first: Confidence, second: Confidence): Confidence =
        if (first.ordinal <= second.ordinal) first else second

    private fun unknown(reason: AssessmentReason, detail: String): PerformanceEstimate.Unknown =
        PerformanceEstimate.Unknown(reason, listOf(Evidence(reason, Confidence.LOW, detail)))

    private data class CorrectionFactors(val low: Double, val likely: Double, val high: Double)

    companion object {
        const val VERSION: Int = 1
        private const val MAX_PROMPT_BATCH_FACTOR: Int = 32
        private const val DEFAULT_PROMPT_REFERENCE_TOKENS: Int = 512
        private const val REFERENCE_PIXELS: Long = 512L * 512L
        private const val REFERENCE_STEPS: Int = 20
        private val ENGINE_VERSION = Regex("[A-Za-z0-9][A-Za-z0-9._+-]*")
    }
}
