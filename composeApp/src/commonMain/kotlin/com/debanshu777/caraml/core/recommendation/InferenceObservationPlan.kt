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
    val assessment = assessedPlans?.values
        ?.filter { it.plan.stableKey == plan.stableKey }
        ?.singleOrNull()
        ?: return null
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
    val rawMemory = when (phase) {
        InferenceObservationPhase.LOAD -> assessment.rawMemoryByPhase.load
        InferenceObservationPhase.GENERATION -> assessment.rawMemoryByPhase.generation
    }
    val predictedHostMemory = rawMemory.hostMemoryBytes
        ?.likelyBytes
        ?.toDouble()
        ?.takeIf(::isValidObservationNumber)
    if (predictedPerformance == null && predictedHostMemory == null) return null
    return InferenceObservationPlan(
        key = observationIdentity.calibrationKey(
            plan = plan,
            engineVersion = engineVersion,
            observationPhase = phase,
        ),
        prediction = ObservationPrediction(
            performance = predictedPerformance,
            hostMemoryBytes = predictedHostMemory,
            performanceMeasurement = performanceMeasurement,
        ),
    )
}

private fun isValidObservationNumber(value: Double): Boolean =
    value.isFinite() && value > 0.0 && value <= 1.0e18
