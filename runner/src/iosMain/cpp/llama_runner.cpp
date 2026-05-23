#include "llama_runner_core.h"

#include <cstring>
#include <iostream>
#include <string>

namespace {

void ios_log(LlamaLogLevel level, const char *msg) {
    if (level == LLAMA_LOG_ERROR) {
        std::cerr << "[LlamaRunner] ERROR: " << (msg ? msg : "") << std::endl;
        return;
    }
    if (level == LLAMA_LOG_WARN) {
        std::cerr << "[LlamaRunner] WARN: " << (msg ? msg : "") << std::endl;
        return;
    }
    std::cout << "[LlamaRunner] " << (msg ? msg : "") << std::endl;
}

} // namespace

extern "C" {

void llama_runner_init(void) {
    llama_runner_core_set_logger(ios_log);
    llama_runner_core_init(nullptr);
}

struct LlamaRunnerConfigFFI {
    int n_ctx;
    int n_ctx_min;
    int n_threads;
    int n_threads_batch;
    int n_batch;
    int n_ubatch;
    int flash_attn;
    int offload_kqv;
    int type_k;
    int type_v;
    int n_gpu_layers;
    int use_mmap;
    float temperature;
    int auto_fit;
};

int llama_runner_load_model_v2(const char *model_path, struct LlamaRunnerConfigFFI ffi_config) {
    LlamaRunnerConfig config;
    config.n_ctx           = ffi_config.n_ctx;
    config.n_ctx_min       = ffi_config.n_ctx_min;
    config.n_threads       = ffi_config.n_threads;
    config.n_threads_batch = ffi_config.n_threads_batch;
    config.n_batch         = ffi_config.n_batch;
    config.n_ubatch        = ffi_config.n_ubatch;
    config.flash_attn      = ffi_config.flash_attn;
    config.offload_kqv     = ffi_config.offload_kqv != 0;
    config.type_k          = ffi_config.type_k;
    config.type_v          = ffi_config.type_v;
    config.n_gpu_layers    = ffi_config.n_gpu_layers;
    config.use_mmap        = ffi_config.use_mmap != 0;
    config.temperature     = ffi_config.temperature;
    config.auto_fit        = ffi_config.auto_fit != 0;
    
    return llama_runner_core_load_model(model_path, config) ? 1 : 0;
}

int llama_runner_load_model(
    const char *model_path,
    int n_ctx,
    int n_threads,
    int n_batch,
    int n_gpu_layers,
    float temperature) {
    LlamaRunnerConfig config;
    config.n_ctx = n_ctx;
    config.n_threads = n_threads;
    config.n_batch = n_batch;
    config.n_gpu_layers = n_gpu_layers;
    config.temperature = temperature;
    return llama_runner_core_load_model(model_path, config) ? 1 : 0;
}

char *llama_runner_generate_text(const char *prompt, int max_tokens, float temperature) {
    const std::string result = llama_runner_core_generate(prompt, max_tokens, temperature);
    return strdup(result.c_str());
}

int llama_runner_start_generate(const char *prompt, int max_tokens, float temperature, const char *grammar) {
    return llama_runner_core_start_generate(prompt, max_tokens, temperature, grammar) ? 1 : 0;
}

char *llama_runner_next_token(void) {
    const char *tok = llama_runner_core_next_token();
    if (tok == nullptr) {
        return nullptr;
    }
    return strdup(tok);
}

void llama_runner_cancel_generate(void) {
    llama_runner_core_cancel_generate();
}

void llama_runner_finalize_generation(void) {
    llama_runner_core_finalize_generation();
}

int llama_runner_process_system_prompt(const char *prompt) {
    return llama_runner_core_process_system_prompt(prompt);
}

int llama_runner_process_user_prompt(const char *prompt, int predict_length, const char *grammar) {
    return llama_runner_core_process_user_prompt(prompt, predict_length, grammar);
}

void llama_runner_unload_model(void) { llama_runner_core_unload(); }

void llama_runner_shutdown(void) { llama_runner_core_shutdown(); }

int llama_runner_get_context_used(void) {
    return llama_runner_core_get_context_used();
}

int llama_runner_get_context_limit(void) {
    return llama_runner_core_get_context_limit();
}

int llama_runner_get_stop_reason(void) {
    return llama_runner_core_get_stop_reason();
}

int llama_runner_get_gpu_layers(void) {
    return llama_runner_core_get_gpu_layers();
}

void llama_runner_clear_context(void) {
    llama_runner_core_clear_context();
}

const char* llama_runner_get_model_architecture(void) {
    return llama_runner_core_get_model_architecture();
}

}
