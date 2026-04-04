package com.debanshu777.diffusionrunner

data class ImageGenParams(
    val prompt: String,
    val negativePrompt: String = "",
    val width: Int = 512,
    val height: Int = 512,
    val steps: Int = 20,
    val cfgScale: Float = 7.0f,
    val seed: Long = -1L,
    val sampleMethod: SampleMethod = SampleMethod.EULER_A,
    val loraPaths: List<String> = emptyList(),
    val loraStrengths: List<Float> = emptyList(),
)