package com.debanshu777.caraml.core.recommendation

import okio.Buffer

/** Descriptor-derived identity used only for versioned, numeric local calibration evidence. */
@ConsistentCopyVisibility
data class ObservationModelIdentity private constructor(
    val architectureFamily: String,
    val quantizationFamily: String,
) {
    companion object {
        fun fromDescriptor(descriptor: ModelDescriptor): ObservationModelIdentity? {
            val architecture = when (descriptor) {
                is LlmModelDescriptor -> descriptor.architecture ?: UNKNOWN
                is DiffusionModelDescriptor -> descriptor.architecture?.name ?: descriptor.family
            }
            val quantization = when (descriptor) {
                is LlmModelDescriptor -> when (val value = descriptor.quantization) {
                    is QuantizationEvidence.Known -> value.quantization
                    is QuantizationEvidence.Mixed -> MIXED
                    QuantizationEvidence.Unknown -> UNKNOWN
                }
                is DiffusionModelDescriptor -> when (descriptor.quantizationDistribution.size) {
                    0 -> UNKNOWN
                    1 -> descriptor.quantizationDistribution.single()
                    else -> descriptor.quantizationDistribution.sorted().joinToString("+")
                        .takeIf(::isSafeObservationLabel)
                        ?: MIXED
                }
            }
            if (!isSafeObservationLabel(architecture) || !isSafeObservationLabel(quantization)) return null
            return ObservationModelIdentity(architecture, quantization)
        }

        private const val UNKNOWN = "unknown"
        private const val MIXED = "mixed"
    }
}

internal fun ObservationModelIdentity.calibrationKey(
    plan: RunPlan,
    engineVersion: String,
    metricKind: MetricKind = MetricKind.PERFORMANCE,
    observationPhase: InferenceObservationPhase? = null,
): CalibrationKey = CalibrationKey(
    backend = plan.backend,
    architectureFamily = architectureFamily,
    quantizationFamily = quantizationFamily,
    workloadBucket = configurationFingerprint(plan, observationPhase),
    engineVersion = engineVersion,
    metricKind = metricKind,
)

private fun configurationFingerprint(
    plan: RunPlan,
    phase: InferenceObservationPhase?,
): String {
    val digest = Buffer().writeUtf8(plan.stableKey).snapshot().sha256().hex()
    val phaseSuffix = phase?.name?.lowercase()?.let { "-$it" }.orEmpty()
    return "plan-v2-$digest$phaseSuffix"
}

internal fun isSafeObservationLabel(value: String): Boolean =
    value.length in 1..128 && OBSERVATION_LABEL.matches(value)

private val OBSERVATION_LABEL = Regex("[A-Za-z0-9][A-Za-z0-9._+:/-]*")
