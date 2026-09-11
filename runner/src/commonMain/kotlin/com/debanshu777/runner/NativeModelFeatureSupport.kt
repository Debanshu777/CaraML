package com.debanshu777.runner

import kotlinx.coroutines.CancellationException

private const val MAX_ARCHITECTURE_LABEL_BYTES = 64
private const val MAX_QUANTIZATION_LABEL_BYTES = 32

enum class NativeFeatureState {
    SUPPORTED,
    UNSUPPORTED,
    UNKNOWN,
}

enum class NativeFeatureProbeReason {
    INVALID_LABEL,
    MALFORMED_NATIVE_PAYLOAD,
    NATIVE_RUNTIME_UNAVAILABLE,
}

data class NativeModelFeatureSupport(
    val architecture: NativeFeatureState,
    val quantization: NativeFeatureState,
    val engineVersion: String?,
    val reason: NativeFeatureProbeReason? = null,
) {
    companion object {
        fun unknown(reason: NativeFeatureProbeReason) = NativeModelFeatureSupport(
            architecture = NativeFeatureState.UNKNOWN,
            quantization = NativeFeatureState.UNKNOWN,
            engineVersion = null,
            reason = reason,
        )
    }
}

internal fun probeNativeModelFeatures(
    architecture: String,
    quantization: String?,
    nativeProbe: (String, String?) -> LongArray?,
): NativeModelFeatureSupport {
    if (!architecture.isSafeNativeFeatureLabel(MAX_ARCHITECTURE_LABEL_BYTES) ||
        (quantization != null && !quantization.isSafeNativeFeatureLabel(MAX_QUANTIZATION_LABEL_BYTES))
    ) {
        return NativeModelFeatureSupport.unknown(NativeFeatureProbeReason.INVALID_LABEL)
    }
    return try {
        decodeNativeModelFeatureSupport(nativeProbe(architecture, quantization))
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        NativeModelFeatureSupport.unknown(NativeFeatureProbeReason.NATIVE_RUNTIME_UNAVAILABLE)
    }
}

internal fun decodeNativeModelFeatureSupport(payload: LongArray?): NativeModelFeatureSupport {
    if (payload == null || payload.size != 3) {
        return NativeModelFeatureSupport.unknown(NativeFeatureProbeReason.MALFORMED_NATIVE_PAYLOAD)
    }
    val architecture = payload[0].toFeatureState()
        ?: return NativeModelFeatureSupport.unknown(NativeFeatureProbeReason.MALFORMED_NATIVE_PAYLOAD)
    val quantization = payload[1].toFeatureState()
        ?: return NativeModelFeatureSupport.unknown(NativeFeatureProbeReason.MALFORMED_NATIVE_PAYLOAD)
    val buildNumber = payload[2]
    if (buildNumber !in 0L..Int.MAX_VALUE.toLong()) {
        return NativeModelFeatureSupport.unknown(NativeFeatureProbeReason.MALFORMED_NATIVE_PAYLOAD)
    }
    return NativeModelFeatureSupport(
        architecture = architecture,
        quantization = quantization,
        engineVersion = "llama.cpp-b$buildNumber",
    )
}

private fun String.isSafeNativeFeatureLabel(maxBytes: Int): Boolean {
    if (isEmpty() || length > maxBytes) return false
    val bytes = encodeToByteArray()
    return bytes.size <= maxBytes && bytes.all { byte -> byte.toInt() in 0x20..0x7e }
}

private fun Long.toFeatureState(): NativeFeatureState? = when (this) {
    0L -> NativeFeatureState.SUPPORTED
    1L -> NativeFeatureState.UNSUPPORTED
    2L -> NativeFeatureState.UNKNOWN
    else -> null
}
