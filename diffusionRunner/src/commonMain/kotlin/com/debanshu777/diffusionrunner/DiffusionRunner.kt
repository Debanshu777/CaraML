package com.debanshu777.diffusionrunner

expect class DiffusionRunner() {
    fun initialize(nativeLibDir: String)
    fun loadModel(config: DiffusionModelConfig): Boolean
    fun txt2Img(params: ImageGenParams): ByteArray?
    fun videoGen(params: VideoGenParams): List<ByteArray>?
    fun release()
    /** Returns [currentStep, totalSteps] for the active generation. Both 0 when idle. */
    fun getStepProgress(): IntArray
    /**
     * Queries metadata for a model file without loading weights into memory.
     * Uses stable-diffusion.cpp's ModelLoader to read GGUF headers.
     *
     * @param modelPath Absolute path to the main model file (.gguf or .safetensors).
     * @return [DiffusionModelMetadata] on success, null if file cannot be read or format is unrecognized.
     */
    fun getDiffusionModelMetadata(modelPath: String): DiffusionModelMetadata?
}