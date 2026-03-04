package com.debanshu777.runner

expect class LlamaRunner() {
    fun initialize(nativeLibDir: String)

    fun loadModel(
        modelPath: String,
        nCtx: Int = 2048,
        nThreads: Int = 4,
        nBatch: Int = 512,
        nGpuLayers: Int = 0,
        temperature: Float = 0.3f,
    ): Boolean

    fun nextToken(): String?

    fun cancelGenerate()

    fun finalizeGeneration()

    fun processSystemPrompt(systemPrompt: String): Int

    fun processUserPrompt(
        userPrompt: String,
        predictLength: Int,
    ): Int

    fun unloadModel()

    fun shutdown()
}
