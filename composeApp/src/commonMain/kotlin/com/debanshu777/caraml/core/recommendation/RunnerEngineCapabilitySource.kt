package com.debanshu777.caraml.core.recommendation

import com.debanshu777.runner.LlamaRunner
import com.debanshu777.runner.NativeFeatureState
import com.debanshu777.runner.NativeModelFeatureSupport
import com.debanshu777.caraml.core.platform.PlatformPaths
import com.debanshu777.caraml.core.platform.discoverWithInitializedRunner
import kotlinx.coroutines.CancellationException

class RunnerEngineCapabilitySource(
    private val runner: LlamaRunner,
) : EngineCapabilitySource {
    override fun supportFor(descriptor: ModelDescriptor): SupportEvidence {
        if (descriptor !is LlmModelDescriptor) return unknownEvidence("llama-native-not-applicable")
        val architecture = descriptor.architecture ?: return SupportEvidence.Unknown(
            reasons = listOf(AssessmentReason.UNKNOWN_ARCHITECTURE),
            evidence = listOf(
                Evidence(
                    reason = AssessmentReason.UNKNOWN_ARCHITECTURE,
                    confidence = Confidence.LOW,
                    detail = "llama-native-missing-architecture",
                ),
            ),
        )
        val quantization = when (val value = descriptor.quantization) {
            is QuantizationEvidence.Known -> value.quantization
            is QuantizationEvidence.Mixed -> return SupportEvidence.Unsupported(
                reasons = listOf(AssessmentReason.MIXED_QUANTIZATION),
                evidence = listOf(
                    Evidence(
                        reason = AssessmentReason.MIXED_QUANTIZATION,
                        confidence = Confidence.HIGH,
                        detail = "llama-native-mixed-quantization",
                    ),
                ),
            )
            QuantizationEvidence.Unknown -> null
        }

        val support = try {
            discoverWithInitializedRunner(
                trustedNativeLibraryDirectory = PlatformPaths::getNativeLibDir,
                initialize = runner::initialize,
                discover = { runner.probeModelFeatures(architecture, quantization) },
            ) ?: return unknownEvidence("llama-native-init-unavailable")
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            return unknownEvidence("llama-native-probe-failed")
        }
        return support.toEvidence(
            quantizationKnown = quantization != null,
            hasUnprobedFeatures = descriptor.requiredEngineFeatures.isNotEmpty(),
        )
    }
}

private fun NativeModelFeatureSupport.toEvidence(
    quantizationKnown: Boolean,
    hasUnprobedFeatures: Boolean,
): SupportEvidence {
    val detail = engineVersion?.let { "llama-native-$it" } ?: "llama-native-probe"
    if (architecture == NativeFeatureState.UNSUPPORTED) {
        return unsupportedEvidence(AssessmentReason.UNSUPPORTED_ARCHITECTURE, detail)
    }
    if (quantizationKnown && quantization == NativeFeatureState.UNSUPPORTED) {
        return unsupportedEvidence(AssessmentReason.UNSUPPORTED_QUANTIZATION, detail)
    }
    if (architecture != NativeFeatureState.SUPPORTED ||
        quantization != NativeFeatureState.SUPPORTED ||
        hasUnprobedFeatures
    ) {
        return unknownEvidence(detail)
    }
    return SupportEvidence.Supported
}

private fun unsupportedEvidence(
    reason: AssessmentReason,
    detail: String,
) = SupportEvidence.Unsupported(
    reasons = listOf(reason),
    evidence = listOf(Evidence(reason, Confidence.HIGH, detail)),
)

private fun unknownEvidence(detail: String) = SupportEvidence.Unknown(
    reasons = listOf(AssessmentReason.ENGINE_SUPPORT_UNKNOWN),
    evidence = listOf(
        Evidence(
            reason = AssessmentReason.ENGINE_SUPPORT_UNKNOWN,
            confidence = Confidence.LOW,
            detail = detail,
        ),
    ),
)
