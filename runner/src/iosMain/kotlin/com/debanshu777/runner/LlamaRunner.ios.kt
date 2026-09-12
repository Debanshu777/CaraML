package com.debanshu777.runner

import com.debanshu777.runner.cpp.LlamaRunnerConfigFFI
import com.debanshu777.runner.cpp.llama_runner_backend_capabilities
import com.debanshu777.runner.cpp.llama_runner_cancel_generate
import com.debanshu777.runner.cpp.llama_runner_clear_context
import com.debanshu777.runner.cpp.llama_runner_finalize_generation
import com.debanshu777.runner.cpp.llama_runner_get_context_limit
import com.debanshu777.runner.cpp.llama_runner_get_context_used
import com.debanshu777.runner.cpp.llama_runner_get_gpu_layers
import com.debanshu777.runner.cpp.llama_runner_get_model_architecture
import com.debanshu777.runner.cpp.llama_runner_get_stop_reason
import com.debanshu777.runner.cpp.llama_runner_init
import com.debanshu777.runner.cpp.llama_runner_load_model_v2
import com.debanshu777.runner.cpp.llama_runner_next_token
import com.debanshu777.runner.cpp.llama_runner_preflight_model
import com.debanshu777.runner.cpp.llama_runner_probe_model_features
import com.debanshu777.runner.cpp.llama_runner_process_system_prompt
import com.debanshu777.runner.cpp.llama_runner_get_content
import com.debanshu777.runner.cpp.llama_runner_get_content_delta
import com.debanshu777.runner.cpp.llama_runner_get_reasoning
import com.debanshu777.runner.cpp.llama_runner_get_reasoning_delta
import com.debanshu777.runner.cpp.llama_runner_process_user_prompt
import com.debanshu777.runner.cpp.llama_runner_shutdown
import com.debanshu777.runner.cpp.llama_runner_supports_thinking
import com.debanshu777.runner.cpp.llama_runner_unload_model
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.CValue
import kotlinx.cinterop.cstr
import kotlinx.cinterop.cValue
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CancellationException
import platform.posix.free

actual class LlamaRunner {
    @OptIn(ExperimentalForeignApi::class)
    actual fun initialize(nativeLibDir: String) {
        llama_runner_init()
    }

    @OptIn(ExperimentalForeignApi::class)
    actual fun loadModel(
        modelPath: String,
        config: NativeRunnerConfig,
    ): Boolean {
        validateLoadModelArgs(modelPath)
        return withFfiConfig(config) { ffiConfig ->
            llama_runner_load_model_v2(modelPath, ffiConfig) != 0
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    actual fun preflightModel(
        modelPath: String,
        config: NativeRunnerConfig,
    ): LlamaPreflightResult = runLlamaPreflight(modelPath, config) { validatedPath, validatedConfig ->
        withFfiConfig(validatedConfig) { ffiConfig ->
            llama_runner_preflight_model(validatedPath, ffiConfig).useContents {
                if (pool_count !in 0..LLAMA_PREFLIGHT_MAX_POOLS) {
                    return@useContents null
                }
                LongArray(LLAMA_PREFLIGHT_HEADER_FIELDS + pool_count * LLAMA_PREFLIGHT_POOL_FIELDS).also { payload ->
                    payload[0] = status.toLong()
                    payload[1] = n_ctx.toLong()
                    payload[2] = n_gpu_layers.toLong()
                    payload[3] = pool_count.toLong()
                    repeat(pool_count) { index ->
                        val offset = LLAMA_PREFLIGHT_HEADER_FIELDS + index * LLAMA_PREFLIGHT_POOL_FIELDS
                        val pool = pools[index]
                        payload[offset] = pool.kind.toLong()
                        payload[offset + 1] = pool.ordinal.toLong()
                        payload[offset + 2] = pool.model_bytes
                        payload[offset + 3] = pool.context_bytes
                        payload[offset + 4] = pool.compute_bytes
                        payload[offset + 5] = pool.free_bytes
                        payload[offset + 6] = pool.total_bytes
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    actual fun backendCapabilities(): List<NativeBackendCapability> = try {
        decodeNativeBackendCapabilities(
            llama_runner_backend_capabilities().useContents {
                if (count !in 0..NATIVE_BACKEND_MAX_DEVICES) {
                    return@useContents null
                }
                LongArray(NATIVE_BACKEND_HEADER_FIELDS + count * NATIVE_BACKEND_DEVICE_FIELDS).also { payload ->
                    payload[0] = count.toLong()
                    repeat(count) { index ->
                        val offset = NATIVE_BACKEND_HEADER_FIELDS + index * NATIVE_BACKEND_DEVICE_FIELDS
                        val device = devices[index]
                        payload[offset] = device.kind.toLong()
                        payload[offset + 1] = device.device_type.toLong()
                        payload[offset + 2] = device.free_bytes
                        payload[offset + 3] = device.total_bytes
                        payload[offset + 4] = device.device_identity_length.toLong()
                        repeat(8) { word ->
                            payload[offset + 5 + word] = device.device_identity_words[word]
                        }
                    }
                }
            },
        )
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        emptyList()
    }

    @OptIn(ExperimentalForeignApi::class)
    actual fun probeModelFeatures(
        architecture: String,
        quantization: String?,
    ): NativeModelFeatureSupport = probeNativeModelFeatures(architecture, quantization) { validatedArchitecture, validatedQuantization ->
        llama_runner_probe_model_features(validatedArchitecture, validatedQuantization).useContents {
            longArrayOf(
                this.architecture.toLong(),
                this.quantization.toLong(),
                engine_build.toLong(),
            )
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    actual fun nextToken(): String? {
        val result = llama_runner_next_token() ?: return null
        return try {
            result.toKString()
        } finally {
            free(result)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    actual fun cancelGenerate() = llama_runner_cancel_generate()

    @OptIn(ExperimentalForeignApi::class)
    actual fun finalizeGeneration() = llama_runner_finalize_generation()

    @OptIn(ExperimentalForeignApi::class)
    actual fun processSystemPrompt(systemPrompt: String): Int {
        require(systemPrompt.isNotBlank()) { "systemPrompt must not be blank" }
        return llama_runner_process_system_prompt(systemPrompt)
    }

    @OptIn(ExperimentalForeignApi::class)
    actual fun processUserPrompt(userPrompt: String, predictLength: Int): Int {
        require(userPrompt.isNotBlank()) { "userPrompt must not be blank" }
        require(predictLength > 0) { "predictLength must be > 0" }
        return llama_runner_process_user_prompt(userPrompt, predictLength)
    }

    @OptIn(ExperimentalForeignApi::class)
    actual fun getReasoning(): String {
        val p = llama_runner_get_reasoning() ?: return ""
        return try { p.toKString() } finally { free(p) }
    }

    @OptIn(ExperimentalForeignApi::class)
    actual fun getContent(): String {
        val p = llama_runner_get_content() ?: return ""
        return try { p.toKString() } finally { free(p) }
    }

    @OptIn(ExperimentalForeignApi::class)
    actual fun supportsThinking(): Boolean = llama_runner_supports_thinking() != 0

    @OptIn(ExperimentalForeignApi::class)
    actual fun getReasoningDelta(): String {
        val p = llama_runner_get_reasoning_delta() ?: return ""
        return try { p.toKString() } finally { free(p) }
    }

    @OptIn(ExperimentalForeignApi::class)
    actual fun getContentDelta(): String {
        val p = llama_runner_get_content_delta() ?: return ""
        return try { p.toKString() } finally { free(p) }
    }

    @OptIn(ExperimentalForeignApi::class)
    actual fun unloadModel() {
        llama_runner_unload_model()
    }

    @OptIn(ExperimentalForeignApi::class)
    actual fun shutdown() {
        llama_runner_shutdown()
    }

    @OptIn(ExperimentalForeignApi::class)
    actual fun getContextUsed(): Int {
        return llama_runner_get_context_used()
    }

    @OptIn(ExperimentalForeignApi::class)
    actual fun getContextLimit(): Int {
        return llama_runner_get_context_limit()
    }

    @OptIn(ExperimentalForeignApi::class)
    actual fun getStopReason(): Int {
        return llama_runner_get_stop_reason()
    }

    @OptIn(ExperimentalForeignApi::class)
    actual fun getGpuLayers(): Int {
        return llama_runner_get_gpu_layers()
    }

    @OptIn(ExperimentalForeignApi::class)
    actual fun clearContext() {
        llama_runner_clear_context()
    }

    @OptIn(ExperimentalForeignApi::class)
    actual fun getModelArchitecture(): String? {
        return llama_runner_get_model_architecture()?.toKString()?.takeIf { it.isNotEmpty() }
    }
}

@OptIn(ExperimentalForeignApi::class)
private inline fun <T> withFfiConfig(
    config: NativeRunnerConfig,
    action: (CValue<LlamaRunnerConfigFFI>) -> T,
): T = memScoped {
    val cpuMask = config.cpuMask.cstr.ptr
    val cpuMaskBatch = config.cpuMaskBatch.cstr.ptr
    action(
        cValue {
            n_ctx = config.nCtx
            n_ctx_min = config.nCtxMin
            n_threads = config.nThreads
            n_threads_batch = config.nThreadsBatch
            n_batch = config.nBatch
            n_ubatch = config.nUbatch
            flash_attn = config.flashAttn
            offload_kqv = if (config.offloadKqv) 1 else 0
            type_k = config.typeK
            type_v = config.typeV
            n_gpu_layers = config.nGpuLayers
            use_mmap = if (config.useMmap) 1 else 0
            use_mlock = if (config.useMlock) 1 else 0
            temperature = config.temperature
            auto_fit = if (config.autoFit) 1 else 0
            cpu_mask = cpuMask
            cpu_mask_batch = cpuMaskBatch
        },
    )
}
