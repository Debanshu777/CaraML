package com.debanshu777.runner

import kotlinx.coroutines.CancellationException

internal const val LLAMA_PREFLIGHT_MAX_POOLS = 17
internal const val LLAMA_PREFLIGHT_HEADER_FIELDS = 4
internal const val LLAMA_PREFLIGHT_POOL_FIELDS = 7

enum class LlamaPreflightReason {
    INVALID_ARGUMENT,
    MODEL_DOES_NOT_FIT,
    INVALID_MODEL,
    NATIVE_RUNTIME_UNAVAILABLE,
    MALFORMED_NATIVE_PAYLOAD,
    INVALID_NATIVE_SIZE,
}

enum class LlamaMemoryPoolKind {
    HOST,
    DISCRETE_GPU,
    INTEGRATED_GPU,
    ACCELERATOR,
    META,
    OTHER,
}

data class LlamaPreflightMemoryPool(
    val kind: LlamaMemoryPoolKind,
    val ordinal: Int,
    val modelBytes: Long,
    val contextBytes: Long,
    val computeBytes: Long,
    val freeBytes: Long,
    val totalBytes: Long,
)

data class LlamaFitReport(
    val fittedContextTokens: Int,
    val fittedGpuLayers: Int,
    val memoryPools: List<LlamaPreflightMemoryPool>,
)

sealed interface LlamaPreflightResult {
    data class Fit(val report: LlamaFitReport) : LlamaPreflightResult
    data class NoFit(
        val reason: LlamaPreflightReason = LlamaPreflightReason.MODEL_DOES_NOT_FIT,
    ) : LlamaPreflightResult

    data class InvalidModel(val reason: LlamaPreflightReason) : LlamaPreflightResult
    data class Unavailable(val reason: LlamaPreflightReason) : LlamaPreflightResult
}

internal fun runLlamaPreflight(
    modelPath: String,
    config: NativeRunnerConfig,
    nativePreflight: (String, NativeRunnerConfig) -> LongArray?,
): LlamaPreflightResult {
    if (!isValidPreflightPath(modelPath) || !isValidPreflightConfig(config)) {
        return LlamaPreflightResult.InvalidModel(LlamaPreflightReason.INVALID_ARGUMENT)
    }
    return try {
        decodeLlamaPreflight(nativePreflight(modelPath, config))
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        LlamaPreflightResult.Unavailable(LlamaPreflightReason.NATIVE_RUNTIME_UNAVAILABLE)
    }
}

internal fun decodeLlamaPreflight(payload: LongArray?): LlamaPreflightResult {
    if (payload == null || payload.size < LLAMA_PREFLIGHT_HEADER_FIELDS) return malformedPreflight()
    val status = payload[0]
    val nCtx = payload[1]
    val nGpuLayers = payload[2]
    val poolCountLong = payload[3]
    if (poolCountLong !in 0L..LLAMA_PREFLIGHT_MAX_POOLS.toLong()) return malformedPreflight()
    val poolCount = poolCountLong.toInt()
    val expectedSize = LLAMA_PREFLIGHT_HEADER_FIELDS + poolCount * LLAMA_PREFLIGHT_POOL_FIELDS
    if (payload.size != expectedSize) return malformedPreflight()

    if (status != 0L) {
        if (poolCount != 0 || nCtx != 0L || nGpuLayers != 0L) return malformedPreflight()
        return when (status) {
            1L -> LlamaPreflightResult.NoFit()
            2L -> LlamaPreflightResult.InvalidModel(LlamaPreflightReason.INVALID_MODEL)
            3L -> LlamaPreflightResult.Unavailable(LlamaPreflightReason.NATIVE_RUNTIME_UNAVAILABLE)
            else -> malformedPreflight()
        }
    }

    if (nCtx !in 1L..Int.MAX_VALUE.toLong() || nGpuLayers !in 0L..Int.MAX_VALUE.toLong() || poolCount == 0) {
        return malformedPreflight()
    }

    val pools = ArrayList<LlamaPreflightMemoryPool>(poolCount)
    repeat(poolCount) { poolIndex ->
        val offset = LLAMA_PREFLIGHT_HEADER_FIELDS + poolIndex * LLAMA_PREFLIGHT_POOL_FIELDS
        val kind = when (payload[offset]) {
            0L -> LlamaMemoryPoolKind.HOST
            1L -> LlamaMemoryPoolKind.DISCRETE_GPU
            2L -> LlamaMemoryPoolKind.INTEGRATED_GPU
            3L -> LlamaMemoryPoolKind.ACCELERATOR
            4L -> LlamaMemoryPoolKind.META
            5L -> LlamaMemoryPoolKind.OTHER
            else -> return malformedPreflight()
        }
        val ordinal = payload[offset + 1]
        if (ordinal != poolIndex.toLong()) return malformedPreflight()
        val values = payload.copyOfRange(offset + 2, offset + LLAMA_PREFLIGHT_POOL_FIELDS)
        if (values.any { it < 0L }) {
            return LlamaPreflightResult.InvalidModel(LlamaPreflightReason.INVALID_NATIVE_SIZE)
        }
        if (values[3] > values[4]) return malformedPreflight()
        pools += LlamaPreflightMemoryPool(
            kind = kind,
            ordinal = poolIndex,
            modelBytes = values[0],
            contextBytes = values[1],
            computeBytes = values[2],
            freeBytes = values[3],
            totalBytes = values[4],
        )
    }
    if (pools.count { it.kind == LlamaMemoryPoolKind.HOST } != 1 ||
        pools.last().kind != LlamaMemoryPoolKind.HOST
    ) {
        return malformedPreflight()
    }
    return LlamaPreflightResult.Fit(
        LlamaFitReport(
            fittedContextTokens = nCtx.toInt(),
            fittedGpuLayers = nGpuLayers.toInt(),
            memoryPools = pools.toList(),
        ),
    )
}

private fun malformedPreflight() =
    LlamaPreflightResult.Unavailable(LlamaPreflightReason.MALFORMED_NATIVE_PAYLOAD)

private fun isValidPreflightPath(modelPath: String): Boolean =
    modelPath.isNotBlank() && '\u0000' !in modelPath && modelPath.encodeToByteArray().size <= 4_096

private fun isValidPreflightConfig(config: NativeRunnerConfig): Boolean =
    isValidNativeRunnerConfig(config)
