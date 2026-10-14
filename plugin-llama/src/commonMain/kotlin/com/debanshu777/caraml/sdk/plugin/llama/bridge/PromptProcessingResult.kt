package com.debanshu777.caraml.sdk.plugin.llama.bridge

enum class PromptProcessingResult {
    Success,
    ContextFull,
    Failure;

    companion object {
        fun fromNativeCode(code: Int): PromptProcessingResult = when (code) {
            0 -> Success
            4 -> ContextFull
            else -> Failure
        }
    }
}
