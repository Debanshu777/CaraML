package com.debanshu777.runner

internal fun validateLoadModelArgs(modelPath: String, config: NativeRunnerConfig) {
    require(modelPath.isNotBlank()) { "modelPath must not be blank" }
    require(isValidNativeRunnerConfig(config)) { "config contains unsupported values" }
}

internal fun isValidNativeRunnerConfig(config: NativeRunnerConfig): Boolean =
    config.nCtx in 0..16_777_216 &&
        config.nCtxMin in 1..16_777_216 &&
        config.nThreads in 1..1_024 &&
        config.nThreadsBatch in 0..1_024 &&
        config.nBatch in 1..1_048_576 &&
        config.nUbatch in 1..config.nBatch &&
        config.nOutputsMaxPerSequence in 0..config.nBatch &&
        config.flashAttn in -1..1 &&
        config.typeK in 0..42 &&
        config.typeV in 0..42 &&
        config.nGpuLayers in -1..65_536 &&
        (config.lazyMode != LlamaLazyMode.ON || config.useMmap) &&
        config.temperature.isFinite() && config.temperature in 0.0f..10.0f
