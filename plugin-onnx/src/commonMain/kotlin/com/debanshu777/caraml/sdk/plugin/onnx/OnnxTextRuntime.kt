package com.debanshu777.caraml.sdk.plugin.onnx

import kotlinx.coroutines.flow.Flow

/** Providers still require a compatible packaged runtime, device and graph. */
enum class OnnxExecutionProvider { AUTO, CPU, XNNPACK, NNAPI, COREML, WEBGPU, QNN_GPU, QNN_HTP }

data class OnnxRuntimeConfig(
    val contextTokens: Int = 2048,
    val threads: Int = 4,
    val provider: OnnxExecutionProvider = OnnxExecutionProvider.AUTO,
    val profilePath: String? = null,
    val temperature: Float = 0f,
    val portableKernels: Boolean = false,
    val basicOptimizations: Boolean = false,
    /** Trades packing/arena reuse for lower load memory; benchmark before selecting. */
    val lowMemory: Boolean = false,
    /** Trusted host-packaged provider libraries, never a path supplied by a remote model. */
    val providerLibraryDirectory: String? = null,
) {
    init {
        require(contextTokens in 128..32768)
        require(threads in 1..32)
        require(temperature.isFinite() && temperature in 0f..2f)
    }
}

data class OnnxChatMessage(val role: String, val content: String)
data class OnnxToken(val id: Int, val text: String)
enum class OnnxStopReason { NONE, EOS, TOKEN_LIMIT, CONTEXT_LIMIT, CANCELLED, ERROR }

interface OnnxTextRuntime {
    val supported: Boolean
    val engineVersion: String
    val contextUsed: Int
    val contextLimit: Int
    val stopReason: OnnxStopReason
    val runtimeDescription: String
    suspend fun load(modelPath: String, config: OnnxRuntimeConfig)
    fun tokenize(messages: List<OnnxChatMessage>): IntArray
    /** Starts a fresh KV cache for the supplied conversation. Native output tensors stay native. */
    fun generate(messages: List<OnnxChatMessage>, maxNewTokens: Int = 128): Flow<OnnxToken>
    fun cancel()
    suspend fun unload()
}

expect fun createOnnxTextRuntime(): OnnxTextRuntime
