package com.debanshu777.runner

sealed class InferenceState {
    data object Uninitialized : InferenceState()
    data object Initializing : InferenceState()
    data object LoadingModel : InferenceState()
    data object ModelReady : InferenceState()
    data object ProcessingPrompt : InferenceState()
    data object Generating : InferenceState()
    data object Cancelling : InferenceState()
    data class Error(val message: String) : InferenceState()
}
