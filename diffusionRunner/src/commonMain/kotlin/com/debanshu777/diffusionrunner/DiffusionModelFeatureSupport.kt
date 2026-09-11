package com.debanshu777.diffusionrunner

import kotlinx.coroutines.CancellationException

private const val MAX_DIFFUSION_ARCHITECTURE_LABEL_BYTES = 64
private const val MAX_DIFFUSION_QUANTIZATION_LABEL_BYTES = 32
private const val MAX_DIFFUSION_ENGINE_VERSION_BYTES = 96

enum class DiffusionGenerationMode(val nativeValue: Int) {
    IMAGE(0),
    VIDEO(1),
}

enum class DiffusionFeatureState {
    SUPPORTED,
    UNSUPPORTED,
    UNKNOWN,
}

enum class DiffusionFeatureProbeReason {
    INVALID_LABEL,
    MALFORMED_NATIVE_PAYLOAD,
    NATIVE_RUNTIME_UNAVAILABLE,
}

data class DiffusionModelFeatureSupport(
    val architecture: DiffusionFeatureState,
    val quantization: DiffusionFeatureState,
    val mode: DiffusionFeatureState,
    val engineVersion: String?,
    val reason: DiffusionFeatureProbeReason? = null,
) {
    companion object {
        fun unknown(reason: DiffusionFeatureProbeReason) = DiffusionModelFeatureSupport(
            architecture = DiffusionFeatureState.UNKNOWN,
            quantization = DiffusionFeatureState.UNKNOWN,
            mode = DiffusionFeatureState.UNKNOWN,
            engineVersion = null,
            reason = reason,
        )
    }
}

internal fun probeDiffusionModelFeatures(
    architecture: String,
    quantization: String?,
    mode: DiffusionGenerationMode,
    nativeProbe: (String, String?, Int) -> LongArray?,
    nativeVersion: () -> String?,
): DiffusionModelFeatureSupport {
    if (!architecture.isSafeDiffusionLabel(MAX_DIFFUSION_ARCHITECTURE_LABEL_BYTES) ||
        (quantization != null && !quantization.isSafeDiffusionLabel(MAX_DIFFUSION_QUANTIZATION_LABEL_BYTES))
    ) {
        return DiffusionModelFeatureSupport.unknown(DiffusionFeatureProbeReason.INVALID_LABEL)
    }
    return try {
        val normalizedQuantization = normalizeDiffusionQuantization(quantization)
        val version = nativeVersion()?.takeIf {
            it.isSafeDiffusionLabel(MAX_DIFFUSION_ENGINE_VERSION_BYTES)
        }
        decodeDiffusionModelFeatureSupport(
            nativeProbe(architecture, normalizedQuantization, mode.nativeValue),
            version,
        )
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        DiffusionModelFeatureSupport.unknown(DiffusionFeatureProbeReason.NATIVE_RUNTIME_UNAVAILABLE)
    }
}

internal fun decodeDiffusionModelFeatureSupport(
    payload: LongArray?,
    engineVersion: String?,
): DiffusionModelFeatureSupport {
    if (payload == null || payload.size != 3) return malformedDiffusionFeatureSupport()
    val architecture = payload[0].toDiffusionFeatureState() ?: return malformedDiffusionFeatureSupport()
    val quantization = payload[1].toDiffusionFeatureState() ?: return malformedDiffusionFeatureSupport()
    val mode = payload[2].toDiffusionFeatureState() ?: return malformedDiffusionFeatureSupport()
    return DiffusionModelFeatureSupport(
        architecture = architecture,
        quantization = quantization,
        mode = mode,
        engineVersion = engineVersion,
    )
}

private fun normalizeDiffusionQuantization(value: String?): String? = when (value) {
    "FP16" -> "F16"
    "FP32" -> "F32"
    "Q3_K_S", "Q3_K_M", "Q3_K_L" -> "Q3_K"
    "Q4_K_S", "Q4_K_M" -> "Q4_K"
    "Q5_K_S", "Q5_K_M" -> "Q5_K"
    else -> value
}

private fun String.isSafeDiffusionLabel(maxBytes: Int): Boolean {
    if (isEmpty() || length > maxBytes) return false
    val bytes = encodeToByteArray()
    return bytes.size <= maxBytes && bytes.all { it.toInt() in 0x20..0x7e }
}

private fun Long.toDiffusionFeatureState(): DiffusionFeatureState? = when (this) {
    0L -> DiffusionFeatureState.SUPPORTED
    1L -> DiffusionFeatureState.UNSUPPORTED
    2L -> DiffusionFeatureState.UNKNOWN
    else -> null
}

private fun malformedDiffusionFeatureSupport() =
    DiffusionModelFeatureSupport.unknown(DiffusionFeatureProbeReason.MALFORMED_NATIVE_PAYLOAD)
