package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.platform.HardwareProfile

class CompatibilityChecker(
    private val engineCapabilitySource: EngineCapabilitySource,
) {
    @Suppress("UNUSED_PARAMETER")
    fun check(descriptor: ModelDescriptor, hardwareProfile: HardwareProfile): Compatibility {
        val hardFailure = when (descriptor) {
            is LlmModelDescriptor -> checkLlm(descriptor)
            is DiffusionModelDescriptor -> checkDiffusion(descriptor)
        }
        if (hardFailure != null) return hardFailure

        return when (val support = engineCapabilitySource.supportFor(descriptor)) {
            SupportEvidence.Supported -> Compatibility.Compatible
            is SupportEvidence.Unsupported -> Compatibility.Incompatible(
                reasons = support.reasons,
                evidence = support.evidence,
            )
            is SupportEvidence.Unknown -> Compatibility.Unknown(
                reasons = support.reasons,
                evidence = support.evidence,
            )
        }
    }

    private fun checkLlm(descriptor: LlmModelDescriptor): Compatibility? {
        if (descriptor.format != ModelFormat.GGUF) {
            return incompatible(AssessmentReason.UNSUPPORTED_FORMAT)
        }
        if (descriptor.files.isEmpty() || descriptor.files.any { modelFormatForPath(it.path) != ModelFormat.GGUF }) {
            return incompatible(AssessmentReason.UNSUPPORTED_FORMAT)
        }
        if (descriptor.checkedTotalFileBytes() is CheckedLong.Invalid) {
            return incompatible(AssessmentReason.INVALID_METADATA)
        }
        val version = descriptor.ggufVersion
        if (version == null) {
            return Compatibility.Unknown(
                reasons = listOf(AssessmentReason.GGUF_VERSION_UNKNOWN),
                evidence = listOf(
                    Evidence(
                        AssessmentReason.GGUF_VERSION_UNKNOWN,
                        Confidence.LOW,
                        "gguf-version",
                    ),
                ),
            )
        }
        if (version !in SUPPORTED_GGUF_VERSIONS) {
            return incompatible(AssessmentReason.UNSUPPORTED_GGUF_VERSION)
        }
        if (descriptor.quantization is QuantizationEvidence.Mixed) {
            return incompatible(AssessmentReason.MIXED_QUANTIZATION)
        }
        return null
    }

    private fun checkDiffusion(descriptor: DiffusionModelDescriptor): Compatibility.Incompatible? {
        if (!descriptor.requiredComponentsPresent) {
            return incompatible(AssessmentReason.MISSING_REQUIRED_COMPONENT)
        }
        if (descriptor.components.isEmpty() || descriptor.components.none { it.isPrimary }) {
            return incompatible(AssessmentReason.MISSING_REQUIRED_COMPONENT)
        }
        if (descriptor.components.any { modelFormatForPath(it.file.path) == null }) {
            return incompatible(AssessmentReason.UNSUPPORTED_FORMAT)
        }
        return null
    }

    private fun incompatible(reason: AssessmentReason) = Compatibility.Incompatible(
        reasons = listOf(reason),
        evidence = listOf(Evidence(reason, Confidence.HIGH)),
    )

    private companion object {
        val SUPPORTED_GGUF_VERSIONS = 2..3
    }
}
