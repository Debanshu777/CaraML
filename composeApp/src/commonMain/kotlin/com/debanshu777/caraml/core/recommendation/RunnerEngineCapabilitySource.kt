package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.rating.SdArchitecture
import com.debanshu777.diffusionrunner.DiffusionFeatureState
import com.debanshu777.diffusionrunner.DiffusionGenerationMode
import com.debanshu777.diffusionrunner.DiffusionModelFeatureSupport
import com.debanshu777.diffusionrunner.DiffusionRunner
import com.debanshu777.runner.LlamaRunner
import com.debanshu777.runner.NativeFeatureState
import com.debanshu777.runner.NativeModelFeatureSupport
import com.debanshu777.caraml.core.platform.PlatformPaths
import com.debanshu777.caraml.core.platform.discoverWithInitializedRunner
import kotlinx.coroutines.CancellationException

class RunnerEngineCapabilitySource(
    private val llamaRunner: LlamaRunner,
    private val diffusionRunner: DiffusionRunner,
) : EngineCapabilitySource {
    override fun supportFor(descriptor: ModelDescriptor): SupportEvidence = when (descriptor) {
        is LlmModelDescriptor -> llamaSupportFor(descriptor)
        is DiffusionModelDescriptor -> diffusionSupportFor(descriptor)
    }

    private fun llamaSupportFor(descriptor: LlmModelDescriptor): SupportEvidence {
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
                initialize = llamaRunner::initialize,
                discover = { llamaRunner.probeModelFeatures(architecture, quantization) },
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

    private fun diffusionSupportFor(descriptor: DiffusionModelDescriptor): SupportEvidence {
        val architecture = descriptor.architecture
            ?.takeUnless { it == SdArchitecture.UNKNOWN }
            ?: return SupportEvidence.Unknown(
                reasons = listOf(AssessmentReason.UNKNOWN_ARCHITECTURE),
                evidence = listOf(
                    Evidence(
                        reason = AssessmentReason.UNKNOWN_ARCHITECTURE,
                        confidence = Confidence.LOW,
                        detail = "diffusion-native-missing-architecture",
                    ),
                ),
            )
        val quantization = when (descriptor.quantizationDistribution.size) {
            0 -> null
            1 -> descriptor.quantizationDistribution.single()
            else -> return SupportEvidence.Unsupported(
                reasons = listOf(AssessmentReason.MIXED_QUANTIZATION),
                evidence = listOf(
                    Evidence(
                        reason = AssessmentReason.MIXED_QUANTIZATION,
                        confidence = Confidence.HIGH,
                        detail = "diffusion-native-mixed-quantization",
                    ),
                ),
            )
        }
        val mode = when (descriptor.mode) {
            DiffusionMode.IMAGE -> DiffusionGenerationMode.IMAGE
            DiffusionMode.VIDEO -> DiffusionGenerationMode.VIDEO
        }
        val support = try {
            discoverWithInitializedRunner(
                trustedNativeLibraryDirectory = PlatformPaths::getNativeLibDir,
                initialize = diffusionRunner::initialize,
                discover = {
                    diffusionRunner.probeModelFeatures(architecture.name, quantization, mode)
                },
            ) ?: return unknownEvidence("diffusion-native-init-unavailable")
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            return unknownEvidence("diffusion-native-probe-failed")
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

private fun DiffusionModelFeatureSupport.toEvidence(
    quantizationKnown: Boolean,
    hasUnprobedFeatures: Boolean,
): SupportEvidence {
    val detail = engineVersion?.let { "diffusion-native-$it" } ?: "diffusion-native-probe"
    if (architecture == DiffusionFeatureState.UNSUPPORTED) {
        return unsupportedEvidence(AssessmentReason.UNSUPPORTED_ARCHITECTURE, detail)
    }
    if (quantizationKnown && quantization == DiffusionFeatureState.UNSUPPORTED) {
        return unsupportedEvidence(AssessmentReason.UNSUPPORTED_QUANTIZATION, detail)
    }
    if (mode == DiffusionFeatureState.UNSUPPORTED) {
        return unsupportedEvidence(AssessmentReason.UNSUPPORTED_ENGINE_FEATURE, detail)
    }
    if (architecture != DiffusionFeatureState.SUPPORTED ||
        (quantizationKnown && quantization != DiffusionFeatureState.SUPPORTED) ||
        mode != DiffusionFeatureState.SUPPORTED ||
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
