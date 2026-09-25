package com.debanshu777.caraml.core.data.inference

private const val RGBA_BYTES_PER_PIXEL = 4L
private const val OUTPUT_COPY_COUNT = 2L
private const val MIN_RUNTIME_HEADROOM_BYTES = 512L * 1024L * 1024L

internal fun estimateMediaOutputBytes(
    width: Int,
    height: Int,
    frames: Int,
): Long = saturatingProduct(
    width.toLong(),
    height.toLong(),
    frames.toLong(),
    RGBA_BYTES_PER_PIXEL,
)

internal fun requiredDiffusionMemoryBytes(
    weightsBytes: Long,
    outputBytes: Long,
): Long {
    if (weightsBytes < 0L || outputBytes < 0L) return Long.MAX_VALUE
    val runtimeHeadroom = maxOf(MIN_RUNTIME_HEADROOM_BYTES, weightsBytes / 4L)
    val outputCopies = saturatingProduct(outputBytes, OUTPUT_COPY_COUNT)
    return saturatingSum(weightsBytes, runtimeHeadroom, outputCopies)
}

internal fun requiredDiffusionAdditionalMemoryBytes(
    loadedWeightsBytes: Long,
    outputBytes: Long,
): Long {
    if (loadedWeightsBytes < 0L || outputBytes < 0L) return Long.MAX_VALUE
    val runtimeHeadroom = maxOf(MIN_RUNTIME_HEADROOM_BYTES, loadedWeightsBytes / 4L)
    return saturatingSum(runtimeHeadroom, saturatingProduct(outputBytes, OUTPUT_COPY_COUNT))
}

internal fun fitsDiffusionMemoryBudget(
    weightsBytes: Long,
    outputBytes: Long,
    budgetBytes: Long,
): Boolean = budgetBytes > 0L &&
    requiredDiffusionMemoryBytes(weightsBytes, outputBytes) <= budgetBytes

internal fun estimateDiffusionWeightsBytes(
    mainFileBytes: Long,
    nativeEstimatedBytes: Long,
    componentBytes: Long,
): Long {
    if (mainFileBytes < 0L || nativeEstimatedBytes < 0L || componentBytes < 0L) {
        return Long.MAX_VALUE
    }
    return saturatingSum(maxOf(mainFileBytes, nativeEstimatedBytes), componentBytes)
}

private fun saturatingProduct(vararg values: Long): Long {
    var result = 1L
    for (value in values) {
        if (value < 0L) return Long.MAX_VALUE
        if (value == 0L) return 0L
        if (result > Long.MAX_VALUE / value) return Long.MAX_VALUE
        result *= value
    }
    return result
}

private fun saturatingSum(vararg values: Long): Long {
    var result = 0L
    for (value in values) {
        if (value < 0L || result > Long.MAX_VALUE - value) return Long.MAX_VALUE
        result += value
    }
    return result
}
