package com.debanshu777.runner

actual class LlamaRunner {

    init {
        try {
            System.loadLibrary("llama_runner")
        } catch (e: UnsatisfiedLinkError) {
            System.err.println("Failed to load llama_runner library: ${e.message}")
            System.err.println("Make sure the native library is built and in java.library.path")
        }
    }

    actual fun initialize(nativeLibDir: String) {
        nativeInit(nativeLibDir)
    }

    actual fun loadModel(
        modelPath: String,
        nCtx: Int,
        nThreads: Int,
        nBatch: Int,
        nGpuLayers: Int,
        temperature: Float,
    ): Boolean {
        validateLoadModelArgs(modelPath)
        return nativeLoadModel(modelPath, nCtx, nThreads, nBatch, nGpuLayers, temperature)
    }

    actual fun nextToken(): String? = nativeNextToken()

    actual fun cancelGenerate() = nativeCancelGenerate()

    actual fun finalizeGeneration() = nativeFinalizeGeneration()

    actual fun processSystemPrompt(systemPrompt: String): Int {
        require(systemPrompt.isNotBlank()) { "systemPrompt must not be blank" }
        return nativeProcessSystemPrompt(systemPrompt)
    }

    actual fun processUserPrompt(userPrompt: String, predictLength: Int): Int {
        require(userPrompt.isNotBlank()) { "userPrompt must not be blank" }
        require(predictLength > 0) { "predictLength must be > 0" }
        return nativeProcessUserPrompt(userPrompt, predictLength)
    }

    actual fun unloadModel() {
        nativeUnloadModel()
    }

    actual fun shutdown() {
        nativeShutdown()
    }

    private external fun nativeInit(libDir: String)
    private external fun nativeLoadModel(
        modelPath: String,
        nCtx: Int,
        nThreads: Int,
        nBatch: Int,
        nGpuLayers: Int,
        temperature: Float,
    ): Boolean

    private external fun nativeNextToken(): String?

    private external fun nativeCancelGenerate()

    private external fun nativeFinalizeGeneration()

    private external fun nativeProcessSystemPrompt(prompt: String): Int

    private external fun nativeProcessUserPrompt(prompt: String, predictLength: Int): Int

    private external fun nativeUnloadModel()
    private external fun nativeShutdown()
}
