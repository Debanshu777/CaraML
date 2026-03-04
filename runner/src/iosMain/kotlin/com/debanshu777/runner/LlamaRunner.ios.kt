package com.debanshu777.runner

import com.debanshu777.runner.cpp.llama_runner_cancel_generate
import com.debanshu777.runner.cpp.llama_runner_finalize_generation
import com.debanshu777.runner.cpp.llama_runner_process_system_prompt
import com.debanshu777.runner.cpp.llama_runner_process_user_prompt
import com.debanshu777.runner.cpp.llama_runner_generate_text
import com.debanshu777.runner.cpp.llama_runner_init
import com.debanshu777.runner.cpp.llama_runner_load_model
import com.debanshu777.runner.cpp.llama_runner_next_token
import com.debanshu777.runner.cpp.llama_runner_shutdown
import com.debanshu777.runner.cpp.llama_runner_start_generate
import com.debanshu777.runner.cpp.llama_runner_unload_model
import kotlinx.cinterop.ExperimentalForeignApi
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
        nCtx: Int,
        nThreads: Int,
        nBatch: Int,
        nGpuLayers: Int,
        temperature: Float,
    ): Boolean {
        validateLoadModelArgs(modelPath)
        return llama_runner_load_model(modelPath, nCtx, nThreads, nBatch, nGpuLayers, temperature) != 0
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
    actual fun unloadModel() {
        llama_runner_unload_model()
    }

    @OptIn(ExperimentalForeignApi::class)
    actual fun shutdown() {
        llama_runner_shutdown()
    }
}
