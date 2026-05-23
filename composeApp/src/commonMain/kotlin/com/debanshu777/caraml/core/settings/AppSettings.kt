package com.debanshu777.caraml.core.settings

/**
 * KV-cache quantization preset for LLM inference.
 *
 * Controls the numeric format used for the attention key (K) and value (V) caches.
 * Lower precision saves memory and speeds up prefill at the cost of minor quality loss.
 *
 * ggml_type int values used internally: F16=1, Q8_0=8, Q4_0=2
 */
enum class KvQuantPreset {
    AUTO,       // Let app decide by RAM budget (default)
    Q4_F16,     // Q4_0 K + F16 V — minimum memory, slightly lower quality
    Q8_Q8,      // Q8_0 K + Q8_0 V — balanced
    F16_F16,    // F16 K + F16 V — maximum quality, most memory
}

data class AppSettings(
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val temperature: Float = DEFAULT_TEMPERATURE,
    val benchmarkMode: Boolean = false,
    val kvQuantPreset: KvQuantPreset = KvQuantPreset.AUTO,
    val useGpu: Boolean = true,
) {
    companion object {
        const val DEFAULT_SYSTEM_PROMPT = "You are a helpful assistant."
        const val DEFAULT_TEMPERATURE = 0.3f
    }
}
