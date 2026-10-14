package com.debanshu777.runner

internal fun validateLoadModelArgs(modelPath: String, config: NativeRunnerConfig) {
    require(isValidLlamaModelPath(modelPath)) { "modelPath contains unsupported values" }
    require(isValidNativeRunnerConfig(config)) { "config contains unsupported values" }
}

internal fun isValidLlamaModelPath(modelPath: String): Boolean =
    modelPath.length in 1..4_096 && modelPath.isNotBlank() &&
        '\u0000' !in modelPath && modelPath.encodeToByteArray().size <= 4_096

internal fun isValidNativeRunnerConfig(config: NativeRunnerConfig): Boolean =
    config.nCtx in 0..16_777_216 &&
        config.nCtxMin in 1..16_777_216 &&
        config.nThreads in 1..1_024 &&
        config.nThreadsBatch in 0..1_024 &&
        config.nBatch in 1..1_048_576 &&
        config.nUbatch in 1..config.nBatch &&
        config.nOutputsMaxPerSequence in 0..config.nBatch &&
        config.flashAttn in -1..1 &&
        isSupportedCacheType(config.typeK) &&
        isSupportedCacheType(config.typeV) &&
        config.nGpuLayers in -1..65_536 &&
        (config.lazyMode != LlamaLazyMode.ON || config.useMmap) &&
        config.temperature.isFinite() && config.temperature in 0.0f..10.0f &&
        isValidCpuMask(config.cpuMask) && isValidCpuMask(config.cpuMaskBatch)

// Keep aligned with llama.cpp common/arg.cpp kv_cache_types and llama_config_validation.h.
private fun isSupportedCacheType(type: Int): Boolean = when (type) {
    0, 1, 2, 3, 6, 7, 8, 20, 30 -> true
    else -> false
}

// The runner accepts decimal CPU indices, ranges and comma-separated combinations.
// This is not the hexadecimal bitmap syntax of upstream parse_cpu_mask.
private fun isValidCpuMask(mask: String): Boolean {
    if (mask.isEmpty()) return true
    if (mask.length > 4_096) return false
    return mask.split(',').all { segment ->
        val bounds = segment.split('-')
        if (bounds.size !in 1..2) return@all false
        val first = parseCpuIndex(bounds[0]) ?: return@all false
        val last = if (bounds.size == 2) parseCpuIndex(bounds[1]) ?: return@all false else first
        first <= last
    }
}

private fun parseCpuIndex(value: String): Int? =
    value.takeIf { it.isNotEmpty() && it.all { character -> character in '0'..'9' } }
        ?.toIntOrNull()?.takeIf { it in 0 until 512 }
