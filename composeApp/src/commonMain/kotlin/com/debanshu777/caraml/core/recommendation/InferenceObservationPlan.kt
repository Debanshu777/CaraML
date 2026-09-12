package com.debanshu777.caraml.core.recommendation

/** Numeric-only description of an admitted inference operation that may be observed locally. */
data class InferenceObservationPlan(
    val key: CalibrationKey,
    val prediction: ObservationPrediction,
)

enum class InferenceObservationPhase {
    LOAD,
    GENERATION,
}

/**
 * Builds an observation plan only from the exact admitted plan and its matching assessment.
 * Repository ids, paths, prompts, generated content, and device identity never enter this value.
 */
fun LoadRequest.toInferenceObservationPlan(
    engineVersion: String,
    phase: InferenceObservationPhase,
): InferenceObservationPlan? {
    val architecture = model.arch?.takeIf(::isSafeObservationLabel) ?: return null
    val quantization = when (val evidence = QuantizationParser.parseFilename(model.filename)) {
        is QuantizationEvidence.Known -> evidence.quantization
        is QuantizationEvidence.Mixed,
        QuantizationEvidence.Unknown,
        -> return null
    }
    val assessment = assessedPlans?.values
        ?.filter { it.plan.stableKey == plan.stableKey }
        ?.singleOrNull()
        ?: return null
    val workload = when (val admittedPlan = plan) {
        is LlmRunPlan -> "ctx-${admittedPlan.contextTokens}"
        is DiffusionRunPlan ->
            "${admittedPlan.mode.name.lowercase()}-${admittedPlan.width}x${admittedPlan.height}-${admittedPlan.steps}"
    }
    val predictedPerformance = when {
        phase == InferenceObservationPhase.LOAD -> null
        assessment.performance is PerformanceEstimate.Llm ->
            assessment.performance.rawAnalyticalDecodeTokensPerSecond
        assessment.performance is PerformanceEstimate.DiffusionImage ->
            assessment.performance.rawAnalyticalSecondsPerStep
        assessment.performance is PerformanceEstimate.DiffusionVideo ->
            assessment.performance.rawAnalyticalSecondsPerStep
        else -> null
    }?.takeIf(::isValidObservationNumber)
    val performanceMeasurement = when (assessment.performance) {
        is PerformanceEstimate.DiffusionImage,
        is PerformanceEstimate.DiffusionVideo,
        -> PerformanceMeasurement.DURATION_PER_UNIT
        else -> PerformanceMeasurement.RATE
    }
    // A process-RSS delta is comparable with a host-pool prediction only. Shared or
    // discrete accelerator allocations require native counters with their own provenance.
    val predictedHostMemory = if (phase == InferenceObservationPhase.LOAD) {
        assessment.hostMemoryBytes?.likelyBytes?.toDouble()?.takeIf(::isValidObservationNumber)
    } else {
        null
    }
    if (predictedPerformance == null && predictedHostMemory == null) return null
    return InferenceObservationPlan(
        key = CalibrationKey(
            backend = plan.backend,
            architectureFamily = architecture,
            quantizationFamily = quantization,
            workloadBucket = workload,
            engineVersion = engineVersion,
        ),
        prediction = ObservationPrediction(
            performance = predictedPerformance,
            hostMemoryBytes = predictedHostMemory,
            performanceMeasurement = performanceMeasurement,
        ),
    )
}

private fun isSafeObservationLabel(value: String): Boolean =
    value.length in 1..128 && OBSERVATION_LABEL.matches(value)

private fun isValidObservationNumber(value: Double): Boolean =
    value.isFinite() && value > 0.0 && value <= 1.0e18

private val OBSERVATION_LABEL = Regex("[A-Za-z0-9][A-Za-z0-9._+:/-]*")
