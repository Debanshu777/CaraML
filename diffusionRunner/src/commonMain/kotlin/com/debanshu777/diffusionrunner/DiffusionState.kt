package com.debanshu777.diffusionrunner

sealed class DiffusionState {
    data object Idle : DiffusionState()
    data object LoadingModel : DiffusionState()
    data object ModelReady : DiffusionState()
    data object Generating : DiffusionState()
    data class Complete(val imageData: ByteArray) : DiffusionState()
    data class Error(val message: String) : DiffusionState()
}