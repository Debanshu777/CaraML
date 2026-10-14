package com.debanshu777.diffusionrunner

data class VideoGenParams(
    val prompt: String,
    val negativePrompt: String = "",
    val width: Int = 512,
    val height: Int = 512,
    val videoFrames: Int = 16,
    val steps: Int = 20,
    val cfgScale: Float = 7.0f,
    val seed: Long = -1L,
    val sampleMethod: SampleMethod = SampleMethod.EULER_A,
    val loraPaths: List<String> = emptyList(),
    val loraStrengths: List<Float> = emptyList(),
)