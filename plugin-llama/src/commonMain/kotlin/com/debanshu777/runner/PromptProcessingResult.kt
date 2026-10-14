package com.debanshu777.runner

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
