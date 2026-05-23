package com.debanshu777.runner

actual class LlamaRunner {

    init {
        try {
            System.loadLibrary("llama_runner")
        } catch (e: UnsatisfiedLinkError) {
            System.err.println("[Runner] ERROR: Failed to load llama_runner library: ${e.message}")
        }
    }

    actual fun initialize(nativeLibDir: String) {
        nativeInit(nativeLibDir)
    }

    actual fun loadModel(
        modelPath: String,
        config: NativeRunnerConfig,
    ): Boolean {
        validateLoadModelArgs(modelPath)
        return nativeLoadModel(modelPath, config)
    }

    actual fun nextToken(): String? = nativeNextToken()

    actual fun cancelGenerate() = nativeCancelGenerate()

    actual fun finalizeGeneration() = nativeFinalizeGeneration()

    actual fun processSystemPrompt(systemPrompt: String): Int {
        require(systemPrompt.isNotBlank()) { "systemPrompt must not be blank" }
        return nativeProcessSystemPrompt(systemPrompt)
    }

    actual fun processUserPrompt(userPrompt: String, predictLength: Int, grammar: String): Int {
        require(userPrompt.isNotBlank()) { "userPrompt must not be blank" }
        require(predictLength > 0) { "predictLength must be > 0" }
        return nativeProcessUserPrompt(userPrompt, predictLength, grammar.ifEmpty { null })
    }

    actual fun unloadModel() {
        nativeUnloadModel()
    }

    actual fun shutdown() {
        nativeShutdown()
    }

    actual fun getContextUsed(): Int = nativeGetContextUsed()

    actual fun getContextLimit(): Int = nativeGetContextLimit()

    actual fun getStopReason(): Int = nativeGetStopReason()

    actual fun getGpuLayers(): Int = nativeGetGpuLayers()

    actual fun clearContext() = nativeClearContext()

    actual fun getModelArchitecture(): String? = nativeGetModelArchitecture()

    private external fun nativeInit(libDir: String)
    private external fun nativeLoadModel(
        modelPath: String,
        config: NativeRunnerConfig,
    ): Boolean

    private external fun nativeNextToken(): String?

    private external fun nativeCancelGenerate()

    private external fun nativeFinalizeGeneration()

    private external fun nativeProcessSystemPrompt(prompt: String): Int

    private external fun nativeProcessUserPrompt(prompt: String, predictLength: Int, grammar: String?): Int

    private external fun nativeUnloadModel()
    private external fun nativeShutdown()
    private external fun nativeGetContextUsed(): Int
    private external fun nativeGetContextLimit(): Int
    private external fun nativeGetStopReason(): Int
    private external fun nativeGetGpuLayers(): Int
    private external fun nativeClearContext()
    private external fun nativeGetModelArchitecture(): String?
}
