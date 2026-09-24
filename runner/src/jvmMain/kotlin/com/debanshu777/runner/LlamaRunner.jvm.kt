package com.debanshu777.runner

import kotlinx.coroutines.CancellationException

actual class LlamaRunner {
    private val nativeAvailable: Boolean = try {
        System.loadLibrary("llama_runner")
        true
    } catch (_: LinkageError) {
        System.err.println("[Runner] ERROR: Native language-model runtime is unavailable")
        false
    } catch (_: SecurityException) {
        false
    }

    private fun requireNativeRuntime() {
        if (!nativeAvailable) throw NativeRuntimeUnavailableException()
    }

    actual fun initialize(nativeLibDir: String) {
        requireNativeRuntime()
        nativeInit(nativeLibDir)
    }

    actual fun engineVersion(): String? = if (nativeAvailable) {
        try {
            parseBoundedLlamaEngineVersion(nativeEngineVersion())
        } catch (_: Throwable) {
            null
        }
    } else {
        null
    }

    actual fun loadModel(
        modelPath: String,
        config: NativeRunnerConfig,
    ): Boolean {
        requireNativeRuntime()
        validateLoadModelArgs(modelPath, config)
        return nativeLoadModel(modelPath, config)
    }

    actual fun preflightModel(
        modelPath: String,
        config: NativeRunnerConfig,
    ): LlamaPreflightResult = runLlamaPreflight(modelPath, config) { validatedPath, validatedConfig ->
        requireNativeRuntime()
        nativePreflightModel(validatedPath, validatedConfig)
    }

    actual fun backendCapabilities(): List<NativeBackendCapability> = if (nativeAvailable) {
        try {
            decodeNativeBackendCapabilities(nativeBackendCapabilities())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            emptyList()
        }
    } else {
        emptyList()
    }

    actual fun calibrateBackend(
        probeToken: Long,
        backend: NativeBackendKind,
        durationMillis: Int,
        bufferBytes: Long,
    ): BackendCalibrationResult {
        if (!isValidBackendCalibrationRequest(probeToken, durationMillis, bufferBytes)) {
            return BackendCalibrationResult.Invalid
        }
        if (!nativeAvailable) return BackendCalibrationResult.Unavailable
        return try {
            decodeBackendCalibrationResult(
                nativeCalibrateBackend(probeToken, backend.ordinal, durationMillis, bufferBytes),
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            BackendCalibrationResult.Unavailable
        }
    }

    actual fun cancelBackendCalibration(probeToken: Long) {
        if (nativeAvailable && probeToken > 0L) nativeCancelBackendCalibration(probeToken)
    }

    actual fun reserveBackendCalibration(probeToken: Long): BackendCalibrationReservation = when {
        !nativeAvailable -> BackendCalibrationReservation.UNAVAILABLE
        probeToken <= 0L -> BackendCalibrationReservation.INVALID
        else -> decodeBackendCalibrationReservation(nativeReserveBackendCalibration(probeToken))
    }

    actual fun abandonBackendCalibration(probeToken: Long): BackendCalibrationAbandonment = when {
        !nativeAvailable -> BackendCalibrationAbandonment.UNAVAILABLE
        probeToken <= 0L -> BackendCalibrationAbandonment.INVALID
        else -> try {
            decodeBackendCalibrationAbandonment(nativeAbandonBackendCalibration(probeToken))
        } catch (_: Throwable) {
            BackendCalibrationAbandonment.UNAVAILABLE
        }
    }

    actual fun probeModelFeatures(
        architecture: String,
        quantization: String?,
    ): NativeModelFeatureSupport = probeNativeModelFeatures(architecture, quantization) { validatedArchitecture, validatedQuantization ->
        requireNativeRuntime()
        nativeProbeModelFeatures(validatedArchitecture, validatedQuantization)
    }

    actual fun nextToken(): String? {
        requireNativeRuntime()
        return nativeNextToken()
    }

    actual fun cancelGenerate() {
        if (nativeAvailable) nativeCancelGenerate()
    }

    actual fun finalizeGeneration() {
        if (nativeAvailable) nativeFinalizeGeneration()
    }

    actual fun processSystemPrompt(systemPrompt: String): Int {
        requireNativeRuntime()
        require(systemPrompt.isNotBlank()) { "systemPrompt must not be blank" }
        return nativeProcessSystemPrompt(systemPrompt)
    }

    actual fun processUserPrompt(userPrompt: String, predictLength: Int): Int {
        requireNativeRuntime()
        require(userPrompt.isNotBlank()) { "userPrompt must not be blank" }
        require(predictLength > 0) { "predictLength must be > 0" }
        return nativeProcessUserPrompt(userPrompt, predictLength)
    }

    actual fun getReasoning(): String = if (nativeAvailable) nativeGetReasoning() else ""
    actual fun getContent(): String = if (nativeAvailable) nativeGetContent() else ""
    actual fun supportsThinking(): Boolean = nativeAvailable && nativeSupportsThinking() != 0
    actual fun getReasoningDelta(): String = if (nativeAvailable) nativeGetReasoningDelta() else ""
    actual fun getContentDelta(): String = if (nativeAvailable) nativeGetContentDelta() else ""

    actual fun unloadModel() {
        if (nativeAvailable) nativeUnloadModel()
    }

    actual fun shutdown() {
        if (nativeAvailable) nativeShutdown()
    }

    actual fun getContextUsed(): Int = if (nativeAvailable) nativeGetContextUsed() else 0

    actual fun getContextLimit(): Int = if (nativeAvailable) nativeGetContextLimit() else 0

    actual fun getStopReason(): Int = if (nativeAvailable) nativeGetStopReason() else StopReason.ERROR

    actual fun getGpuLayers(): Int = if (nativeAvailable) nativeGetGpuLayers() else 0

    actual fun clearContext() {
        if (nativeAvailable) nativeClearContext()
    }

    actual fun getModelArchitecture(): String? =
        if (nativeAvailable) nativeGetModelArchitecture() else null

    private external fun nativeInit(libDir: String)
    private external fun nativeEngineVersion(): String?
    private external fun nativeLoadModel(
        modelPath: String,
        config: NativeRunnerConfig,
    ): Boolean

    private external fun nativePreflightModel(
        modelPath: String,
        config: NativeRunnerConfig,
    ): LongArray?

    private external fun nativeBackendCapabilities(): LongArray?

    private external fun nativeCalibrateBackend(
        probeToken: Long,
        backend: Int,
        durationMillis: Int,
        bufferBytes: Long,
    ): LongArray?

    private external fun nativeCancelBackendCalibration(probeToken: Long)

    private external fun nativeReserveBackendCalibration(probeToken: Long): Int

    private external fun nativeAbandonBackendCalibration(probeToken: Long): Int

    private external fun nativeProbeModelFeatures(
        architecture: String,
        quantization: String?,
    ): LongArray?

    private external fun nativeNextToken(): String?

    private external fun nativeCancelGenerate()

    private external fun nativeFinalizeGeneration()

    private external fun nativeProcessSystemPrompt(prompt: String): Int

    private external fun nativeProcessUserPrompt(prompt: String, predictLength: Int): Int

    private external fun nativeGetReasoning(): String
    private external fun nativeGetContent(): String
    private external fun nativeSupportsThinking(): Int
    private external fun nativeGetReasoningDelta(): String
    private external fun nativeGetContentDelta(): String

    private external fun nativeUnloadModel()
    private external fun nativeShutdown()
    private external fun nativeGetContextUsed(): Int
    private external fun nativeGetContextLimit(): Int
    private external fun nativeGetStopReason(): Int
    private external fun nativeGetGpuLayers(): Int
    private external fun nativeClearContext()
    private external fun nativeGetModelArchitecture(): String?
}
