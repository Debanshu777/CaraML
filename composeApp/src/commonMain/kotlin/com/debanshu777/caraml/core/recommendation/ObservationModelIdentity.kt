package com.debanshu777.caraml.core.recommendation

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
): CalibrationKey = CalibrationKey(
    backend = plan.backend,
    architectureFamily = architectureFamily,
    quantizationFamily = quantizationFamily,
    workloadBucket = when (plan) {
        is LlmRunPlan -> "ctx-${plan.contextTokens}"
        is DiffusionRunPlan -> "${plan.mode.name.lowercase()}-${plan.width}x${plan.height}-${plan.steps}"
    },
    engineVersion = engineVersion,
    metricKind = metricKind,
)

internal fun isSafeObservationLabel(value: String): Boolean =
    value.length in 1..128 && OBSERVATION_LABEL.matches(value)

private val OBSERVATION_LABEL = Regex("[A-Za-z0-9][A-Za-z0-9._+:/-]*")
