package com.debanshu777.caraml.core.settings

data class AppSettings(
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val temperature: Float = DEFAULT_TEMPERATURE,
) {
    companion object {
        const val DEFAULT_SYSTEM_PROMPT = "You are a helpful assistant."
        const val DEFAULT_TEMPERATURE = 0.3f
    }
}
