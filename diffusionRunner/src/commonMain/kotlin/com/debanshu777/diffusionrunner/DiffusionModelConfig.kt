package com.debanshu777.diffusionrunner

data class DiffusionModelConfig(
    val modelPath: String,
    val vaePath: String = "",
    val llmPath: String = "",
    val clipLPath: String = "",
    val clipGPath: String = "",
    val t5xxlPath: String = "",
    val offloadToCpu: Boolean = false,
    val keepClipOnCpu: Boolean = false,
    val keepVaeOnCpu: Boolean = false,
    val diffusionFlashAttn: Boolean = false,
    val enableMmap: Boolean = false,
    val diffusionConvDirect: Boolean = false,
    /** Release weights from RAM/VRAM after each generation; weights reload on next gen. */
    val freeParamsImmediately: Boolean = false,
    val wtype: Int = -1,
    val flowShift: Float = Float.POSITIVE_INFINITY,
    val nThreads: Int = -1,
    /** Prediction type: -1=auto-detect, 0=EPS, 1=V_PRED, 2=EDM_V_PRED, 3=FLOW, 4=FLUX_FLOW, 5=FLUX2_FLOW */
    val prediction: Int = -1,
    /** Optional path to a TAESD safetensors file; replaces the full VAE decoder for fast preview. Empty = disabled. */
    val taesdPath: String = "",
    /** Enable VAE tiling for large images (>512×512) to avoid OOM during decode. */
    val vaeTiling: Boolean = false,
)