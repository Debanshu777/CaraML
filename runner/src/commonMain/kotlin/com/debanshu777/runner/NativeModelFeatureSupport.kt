package com.debanshu777.runner

import kotlinx.coroutines.CancellationException

private const val MAX_ARCHITECTURE_LABEL_BYTES = 64
private const val MAX_QUANTIZATION_LABEL_BYTES = 32
private const val MAX_ENGINE_VERSION_BYTES = 72

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
        val nativeQuantization = when (quantization) {
            "FP16" -> "F16"
            "FP32" -> "F32"
            else -> quantization
        }
        decodeNativeModelFeatureSupport(nativeProbe(architecture, nativeQuantization))
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

internal fun parseBoundedLlamaEngineVersion(rawVersion: String?): String? {
    if (rawVersion == null || rawVersion.isEmpty() || rawVersion.length > MAX_ENGINE_VERSION_BYTES) {
        return null
    }
    val valid = rawVersion.all { character ->
        character in 'A'..'Z' || character in 'a'..'z' || character in '0'..'9' ||
            character == '.' || character == '_' || character == '+' || character == '-'
    }
    return rawVersion.takeIf { valid }?.let { "llama.cpp-$it" }
}

private fun Long.toFeatureState(): NativeFeatureState? = when (this) {
    0L -> NativeFeatureState.SUPPORTED
    1L -> NativeFeatureState.UNSUPPORTED
    2L -> NativeFeatureState.UNKNOWN
    else -> null
}
