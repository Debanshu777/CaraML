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
    val wtype: Int = -1,
    val flowShift: Float = Float.POSITIVE_INFINITY,
    val nThreads: Int = -1,
)