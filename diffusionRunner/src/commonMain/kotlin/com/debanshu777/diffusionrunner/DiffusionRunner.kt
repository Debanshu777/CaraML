package com.debanshu777.diffusionrunner

expect class DiffusionRunner() {
    fun initialize(nativeLibDir: String)
    fun loadModel(config: DiffusionModelConfig): Boolean
    fun txt2Img(params: ImageGenParams): ByteArray?
    fun videoGen(params: VideoGenParams): List<ByteArray>?
    fun release()
    /** Returns [currentStep, totalSteps] for the active generation. Both 0 when idle. */
    fun getStepProgress(): IntArray
}