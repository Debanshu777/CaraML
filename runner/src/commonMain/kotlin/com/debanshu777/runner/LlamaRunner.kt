package com.debanshu777.runner

expect class LlamaRunner() {
    fun initialize(nativeLibDir: String)

    fun loadModel(
        modelPath: String,
        config: NativeRunnerConfig,
    ): Boolean

    fun nextToken(): String?

    fun cancelGenerate()

    fun finalizeGeneration()

    fun processSystemPrompt(systemPrompt: String): Int

    fun processUserPrompt(
        userPrompt: String,
        predictLength: Int,
    ): Int

    fun getReasoning(): String

    fun getContent(): String

    fun supportsThinking(): Boolean

    fun getReasoningDelta(): String

    fun getContentDelta(): String

    fun unloadModel()

    fun shutdown()

    fun getContextUsed(): Int

    fun getContextLimit(): Int

    fun getStopReason(): Int

    fun getGpuLayers(): Int

    fun clearContext()

    fun getModelArchitecture(): String?
}
