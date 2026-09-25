package com.debanshu777.runner

expect class LlamaRunner() {
    fun initialize(nativeLibDir: String)

    fun engineVersion(): String?

    fun loadModel(
        modelPath: String,
        config: NativeRunnerConfig,
    ): Boolean

    fun preflightModel(
        modelPath: String,
        config: NativeRunnerConfig,
    ): LlamaPreflightResult

    fun backendCapabilities(): List<NativeBackendCapability>

    fun calibrateBackend(
        probeToken: Long,
        backend: NativeBackendKind,
        durationMillis: Int,
        bufferBytes: Long,
    ): BackendCalibrationResult

    fun reserveBackendCalibration(probeToken: Long): BackendCalibrationReservation

    fun cancelBackendCalibration(probeToken: Long)

    fun abandonBackendCalibration(probeToken: Long): BackendCalibrationAbandonment

    fun probeModelFeatures(
        architecture: String,
        quantization: String?,
    ): NativeModelFeatureSupport

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
