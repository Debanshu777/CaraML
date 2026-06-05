package com.debanshu777.runner

data class NativeRunnerConfig(
    val nCtx: Int = 0,
    val nCtxMin: Int = 512,
    val nThreads: Int = 4,
    val nThreadsBatch: Int = 0,
    val nBatch: Int = 512,
    val nUbatch: Int = 512,
    val flashAttn: Int = -1,
    val offloadKqv: Boolean = true,
    val typeK: Int = 1,
    val typeV: Int = 1,
    val nGpuLayers: Int = -1,
    val useMmap: Boolean = true,
    val useMlock: Boolean = false,
    val temperature: Float = 0.3f,
    val autoFit: Boolean = true,
    val cpuMask: String = "",       // e.g. "4-7" or "4,5,6,7" or "" (no pinning)
    val cpuMaskBatch: String = "",  // separate mask for batch prefill threads
)
