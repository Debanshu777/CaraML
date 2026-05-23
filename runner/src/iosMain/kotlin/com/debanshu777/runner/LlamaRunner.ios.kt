package com.debanshu777.runner

import com.debanshu777.runner.cpp.LlamaRunnerConfigFFI
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
import com.debanshu777.runner.cpp.llama_runner_process_system_prompt
import com.debanshu777.runner.cpp.llama_runner_process_user_prompt
import com.debanshu777.runner.cpp.llama_runner_shutdown
import com.debanshu777.runner.cpp.llama_runner_unload_model
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cValue
import kotlinx.cinterop.toKString
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
        val ffiConfig = cValue<LlamaRunnerConfigFFI> {
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
            temperature = config.temperature
            auto_fit = if (config.autoFit) 1 else 0
        }
        return llama_runner_load_model_v2(modelPath, ffiConfig) != 0
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
    actual fun processUserPrompt(userPrompt: String, predictLength: Int, grammar: String): Int {
        require(userPrompt.isNotBlank()) { "userPrompt must not be blank" }
        require(predictLength > 0) { "predictLength must be > 0" }
        return llama_runner_process_user_prompt(userPrompt, predictLength, grammar.ifEmpty { null })
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
