package com.debanshu777.runner

enum class LlamaLazyMode(val nativeValue: Int) {
    OFF(0),
    AUTO(1),
    ON(2),
}

/**
 * Single-sequence inference settings. Context and batch sizes are token counts;
 * KV types are independent of the GGUF weight quantization. Positive GPU layer
 * counts remain explicit when fitting; -1 requests full offload. CPU affinity
 * uses decimal indices/ranges/lists, not upstream hexadecimal masks.
 */
data class NativeRunnerConfig(
    val nCtx: Int = 0,
    val nCtxMin: Int = 512,
    val nThreads: Int = 4,
    val nThreadsBatch: Int = 0,
    val nBatch: Int = 512,
    val nUbatch: Int = 512,
    val nOutputsMaxPerSequence: Int = 1,
    val flashAttn: Int = -1,
    val offloadKqv: Boolean = true,
    val typeK: Int = 1,
    val typeV: Int = 1,
    val nGpuLayers: Int = -1,
    val useMmap: Boolean = true,
    val useMlock: Boolean = false,
    val lazyMode: LlamaLazyMode = LlamaLazyMode.AUTO,
    val temperature: Float = 0.3f,
    val autoFit: Boolean = true,
    val cpuMask: String = "",       // e.g. "4-7" or "4,5,6,7" or "" (no pinning)
    val cpuMaskBatch: String = "",  // separate mask for batch prefill threads
)
