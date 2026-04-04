package com.debanshu777.diffusionrunner

actual class DiffusionRunner {
    private var handle: Long = 0L

    init {
        System.loadLibrary("diffusion_runner")
    }

    actual fun initialize(nativeLibDir: String) {
        nativeInit(nativeLibDir)
    }

    actual fun loadModel(config: DiffusionModelConfig): Boolean {
        validateModelConfig(config)
        handle = nativeLoadModel(config)
        return handle != 0L
    }

    actual fun txt2Img(params: ImageGenParams): ByteArray? {
        if (handle == 0L) return null
        validateImageGenParams(params)
        return nativeTxt2Img(
            handle, params.prompt, params.negativePrompt,
            params.width, params.height, params.steps,
            params.cfgScale, params.seed, params.sampleMethod.value,
            params.loraPaths.toTypedArray(),
            params.loraStrengths.toFloatArray()
        )
    }

    actual fun videoGen(params: VideoGenParams): List<ByteArray>? {
        if (handle == 0L) return null
        val frames = nativeVideoGen(
            handle, params.prompt, params.negativePrompt,
            params.width, params.height, params.videoFrames,
            params.steps, params.cfgScale, params.seed,
            params.sampleMethod.value,
            params.loraPaths.toTypedArray(),
            params.loraStrengths.toFloatArray()
        ) ?: return null
        return frames.toList()
    }

    actual fun release() {
        if (handle != 0L) {
            nativeRelease(handle)
            handle = 0L
        }
    }

    private external fun nativeInit(libDir: String)
    private external fun nativeLoadModel(config: DiffusionModelConfig): Long
    private external fun nativeTxt2Img(
        handle: Long, prompt: String, negative: String,
        width: Int, height: Int, steps: Int,
        cfg: Float, seed: Long, sampleMethod: Int,
        loraPaths: Array<String>?, loraStrengths: FloatArray?
    ): ByteArray?

    private external fun nativeVideoGen(
        handle: Long, prompt: String, negative: String,
        width: Int, height: Int, videoFrames: Int,
        steps: Int, cfg: Float, seed: Long,
        sampleMethod: Int,
        loraPaths: Array<String>?, loraStrengths: FloatArray?
    ): Array<ByteArray>?

    private external fun nativeRelease(handle: Long)
}